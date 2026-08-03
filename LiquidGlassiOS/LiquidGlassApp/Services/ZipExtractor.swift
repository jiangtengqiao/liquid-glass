import Foundation
import Compression

// ─────────────────────────────────────────────────────────────────
// ZIP 解压器 —— 对应 Android 端 java.util.zip.ZipInputStream
//
// 解析 ZIP 文件格式（PKZIP），支持：
//   - Stored（method 0，无压缩，直接拷贝）
//   - Deflate（method 8，使用 Compression 框架 COMPRESSION_DEFLATE 解压）
//
// 不依赖第三方库（如 SSZipArchive / ZipArchive），纯 Foundation + Compression 实现。
// ─────────────────────────────────────────────────────────────────

enum ZipExtractor {

    private static let localFileHeaderSignature: UInt32 = 0x04034b50
    private static let endOfCentralDirSignature: UInt32 = 0x06054b50

    /// 解压 ZIP Data 到指定目录。
    /// - Parameters:
    ///   - data: 完整的 ZIP 文件数据
    ///   - destination: 解压目标目录
    ///   - onFile: 每解出一个文件时的回调（文件名）
    /// - Returns: 成功时返回解出的文件数，失败时返回错误
    static func unzip(_ data: Data,
                      to destination: URL,
                      onFile: (String) -> Void = { _ in }) -> Result<Int, Error> {
        var fileCount = 0
        var offset = 0

        // 确保目标目录存在
        try? FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        while offset < data.count - 4 {
            // 读取签名
            let signature = data.readUInt32(at: offset)
            if signature != localFileHeaderSignature {
                // 到达中央目录或其他结构，停止
                if signature == endOfCentralDirSignature || signature == 0x02014b50 {
                    break
                }
                offset += 1
                continue
            }

            // 解析 Local File Header
            // 偏移布局：sig(4) + version(2) + flags(2) + method(2) + time(2) + date(2) + crc(4) + compSize(4) + uncompSize(4) + nameLen(2) + extraLen(2) = 30 字节固定头
            let headerSize = 30
            guard offset + headerSize <= data.count else { break }

            let compressionMethod = data.readUInt16(at: offset + 8)
            let compressedSize = Int(data.readUInt32(at: offset + 18))
            let uncompressedSize = Int(data.readUInt32(at: offset + 22))
            let fileNameLength = Int(data.readUInt16(at: offset + 26))
            let extraFieldLength = Int(data.readUInt16(at: offset + 28))

            let fileNameStart = offset + headerSize
            guard fileNameStart + fileNameLength <= data.count else { break }
            let fileNameData = data.subdata(in: fileNameStart..<(fileNameStart + fileNameLength))
            let fileName = String(data: fileNameData, encoding: .utf8) ?? "unknown"

            let dataStart = fileNameStart + fileNameLength + extraFieldLength
            guard dataStart + compressedSize <= data.count else { break }
            let compressedData = data.subdata(in: dataStart..<(dataStart + compressedSize))

            // 跳过目录条目
            if fileName.hasSuffix("/") {
                offset = dataStart + compressedSize
                continue
            }

            // 解压数据
            let decompressed: Data
            switch compressionMethod {
            case 0: // Stored
                decompressed = compressedData
            case 8: // Deflate
                guard let inflated = inflate(compressedData, expectedSize: uncompressedSize) else {
                    offset = dataStart + compressedSize
                    continue
                }
                decompressed = inflated
            default:
                // 不支持的压缩方法，跳过
                offset = dataStart + compressedSize
                continue
            }

            // 写入文件
            let outputPath = destination.appendingPathComponent(fileName)

            // 创建父目录
            let parentDir = outputPath.deletingLastPathComponent()
            try? FileManager.default.createDirectory(at: parentDir, withIntermediateDirectories: true)

            do {
                try decompressed.write(to: outputPath)
                fileCount += 1
                onFile(fileName)
            } catch {
                // 写入失败，跳过此文件继续
            }

            offset = dataStart + compressedSize
        }

        return .success(fileCount)
    }

    /// 使用 Compression 框架解压 raw deflate 数据。
    private static func inflate(_ compressed: Data, expectedSize: Int) -> Data? {
        // 预分配解压缓冲区（若 expectedSize 为 0 则给一个较大的默认值）
        let capacity = expectedSize > 0 ? expectedSize : compressed.count * 10
        var outputBuffer = [UInt8](repeating: 0, count: capacity + 1024)

        let result = compressed.withUnsafeBytes { (srcPtr: UnsafeRawBufferPointer) -> Int in
            guard let srcBase = srcPtr.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return outputBuffer.withUnsafeMutableBufferPointer { dstPtr in
                compression_decode_buffer(
                    dstPtr.baseAddress!,
                    dstPtr.count,
                    srcBase,
                    compressed.count,
                    nil,
                    COMPRESSION_DEFLATE
                )
            }
        }

        guard result > 0 else { return nil }
        return Data(outputBuffer.prefix(result))
    }
}

// MARK: - Data 读取辅助
private extension Data {
    func readUInt16(at offset: Int) -> UInt16 {
        guard offset + 2 <= count else { return 0 }
        return UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func readUInt32(at offset: Int) -> UInt32 {
        guard offset + 4 <= count else { return 0 }
        return UInt32(self[offset])
             | (UInt32(self[offset + 1]) << 8)
             | (UInt32(self[offset + 2]) << 16)
             | (UInt32(self[offset + 3]) << 24)
    }
}
