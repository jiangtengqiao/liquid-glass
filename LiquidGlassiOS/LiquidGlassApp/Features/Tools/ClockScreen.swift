import SwiftUI
import CoreLocation

// ─────────────────────────────────────────────────────────────────
// 时钟 · 天气 —— 对应 Android 端 ui/ClockScreen.kt
//
// 关键实现：
//   1. 数字时钟：Timer.publish 每 1 秒刷新 currentDate，DateFormatter 格式化
//      （对应 Android 的 LaunchedEffect + delay(1000) 循环）
//   2. 模拟时钟：Canvas 绘制表盘刻度、时/分/秒指针；指针角度由时间分量换算
//   3. 天气卡片：CLLocationManager 请求定位，CLGeocoder 反查城市名；
//      权限未授予或定位中均显示"定位中..."；温度/天气状况为占位数据
// ─────────────────────────────────────────────────────────────────

/// 定位管理器：封装 CLLocationManager 权限请求与反查城市名（对应 Android LocationManager）。
private final class ClockLocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var locationLabel: String = "定位中..."
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func start() {
        let status = manager.authorizationStatus
        switch status {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            // 权限未授予，保持"定位中..."（按需求）
            break
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.requestLocation()
        }
        // 权限被拒/受限时保持"定位中..."不变
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        manager.stopUpdatingLocation()
        reverseGeocode(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失败保持"定位中..."（按需求不显示错误）
    }

    private func reverseGeocode(_ location: CLLocation) {
        let geocoder = CLGeocoder()
        geocoder.reverseGeocodeLocation(location) { placemarks, _ in
            DispatchQueue.main.async {
                if let placemark = placemarks?.first {
                    let city = placemark.locality
                        ?? placemark.subLocality
                        ?? placemark.administrativeArea
                        ?? "当前位置"
                    self.locationLabel = city
                } else {
                    self.locationLabel = "当前位置"
                }
            }
        }
    }
}

struct ClockScreen: View {
    var onBack: () -> Void

    @State private var currentDate = Date()
    @StateObject private var locationManager = ClockLocationManager()

    /// 每 1 秒触发一次刷新（对应 Android LaunchedEffect + delay(1000)）。
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        f.locale = Locale(identifier: "zh_CN")
        return f
    }()

    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy年MM月dd日 EEEE"
        f.locale = Locale(identifier: "zh_CN")
        return f
    }()

    var body: some View {
        let theme = Themes.midnightDark
        ZStack {
            FluidBackground(animTime: 0, theme: theme)

            VStack(spacing: 16) {
                topBar(theme: theme)

                ScrollView {
                    VStack(spacing: 16) {
                        digitalClockCard(theme: theme)
                        analogClockCard(theme: theme)
                        weatherCard(theme: theme)
                    }
                }
            }
            .padding(.top, 50)
            .padding(.bottom, 24)
        }
        .onReceive(timer) { _ in
            currentDate = Date()
        }
        .onAppear {
            locationManager.start()
        }
    }

    // MARK: - 顶部栏
    private func topBar(theme: AppTheme) -> some View {
        HStack {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").foregroundStyle(theme.textSecondary)
            }
            Text("时钟·天气").font(.headline).foregroundStyle(theme.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    // MARK: - 数字时钟卡片
    private func digitalClockCard(theme: AppTheme) -> some View {
        VStack(spacing: 8) {
            Text(timeFormatter.string(from: currentDate))
                .font(.system(size: 64, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(theme.fluidCyan)
            Text(dateFormatter.string(from: currentDate))
                .font(.subheadline)
                .foregroundStyle(theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .glassSurface(cornerRadius: 24, theme: theme)
        .padding(.horizontal, 16)
    }

    // MARK: - 模拟时钟卡片
    private func analogClockCard(theme: AppTheme) -> some View {
        AnalogClock(date: currentDate, theme: theme)
            .frame(width: 220, height: 220)
            .padding(24)
            .glassSurface(cornerRadius: 24, theme: theme)
            .padding(.horizontal, 16)
    }

    // MARK: - 天气卡片
    private func weatherCard(theme: AppTheme) -> some View {
        HStack(spacing: 20) {
            Image(systemName: "sun.max.fill")
                .font(.system(size: 44))
                .foregroundStyle(theme.fluidOrange)

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("22°")
                        .font(.system(size: 36, weight: .semibold, design: .rounded))
                        .foregroundStyle(theme.textPrimary)
                    Text("晴")
                        .font(.title3)
                        .foregroundStyle(theme.textSecondary)
                }
                HStack(spacing: 6) {
                    Image(systemName: "location.fill")
                        .font(.caption)
                        .foregroundStyle(theme.fluidTeal)
                    Text(locationManager.locationLabel)
                        .font(.caption)
                        .foregroundStyle(theme.textSecondary)
                }
            }

            Spacer()
        }
        .padding(20)
        .glassSurface(cornerRadius: 24, theme: theme)
        .padding(.horizontal, 16)
    }
}

// MARK: - 模拟时钟子视图
private struct AnalogClock: View {
    let date: Date
    let theme: AppTheme

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 6

            // 刻度：60 格，整点为长刻度
            for i in 0..<60 {
                let angle = CGFloat(i) * 2 * .pi / 60 - .pi / 2
                let isMajor = i % 5 == 0
                let inner = radius * (isMajor ? 0.80 : 0.88)
                let outer = radius * 0.94
                let p1 = CGPoint(x: center.x + cos(angle) * inner,
                                 y: center.y + sin(angle) * inner)
                let p2 = CGPoint(x: center.x + cos(angle) * outer,
                                 y: center.y + sin(angle) * outer)
                var path = Path()
                path.move(to: p1)
                path.addLine(to: p2)
                context.stroke(path,
                               with: .color(isMajor ? theme.textSecondary : theme.textTertiary),
                               lineWidth: isMajor ? 2 : 1)
            }

            // 时间分量
            let cal = Calendar.current
            let comps = cal.dateComponents([.hour, .minute, .second], from: date)
            let hour = CGFloat(comps.hour ?? 0)
            let minute = CGFloat(comps.minute ?? 0)
            let second = CGFloat(comps.second ?? 0)

            let hourAngle = (hour + minute / 60) * 2 * .pi / 12 - .pi / 2
            let minuteAngle = (minute + second / 60) * 2 * .pi / 60 - .pi / 2
            let secondAngle = second * 2 * .pi / 60 - .pi / 2

            // 时针
            drawHand(context: context, center: center, angle: hourAngle,
                     length: radius * 0.50, width: 4, color: theme.textPrimary)
            // 分针
            drawHand(context: context, center: center, angle: minuteAngle,
                     length: radius * 0.72, width: 3, color: theme.textPrimary)
            // 秒针
            drawHand(context: context, center: center, angle: secondAngle,
                     length: radius * 0.82, width: 1.5, color: theme.fluidCyan)

            // 中心点：外圈青色 + 内圈底色
            context.fill(Path(ellipseIn: CGRect(x: center.x - 5, y: center.y - 5,
                                                width: 10, height: 10)),
                         with: .color(theme.fluidCyan))
            context.fill(Path(ellipseIn: CGRect(x: center.x - 2, y: center.y - 2,
                                                width: 4, height: 4)),
                         with: .color(theme.bgDark))
        }
    }

    private func drawHand(context: GraphicsContext, center: CGPoint, angle: CGFloat,
                          length: CGFloat, width: CGFloat, color: Color) {
        let end = CGPoint(x: center.x + cos(angle) * length,
                          y: center.y + sin(angle) * length)
        var path = Path()
        path.move(to: center)
        path.addLine(to: end)
        context.stroke(path, with: .color(color), lineWidth: width)
    }
}
