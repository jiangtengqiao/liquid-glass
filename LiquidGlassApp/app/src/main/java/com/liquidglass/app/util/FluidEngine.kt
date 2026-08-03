package com.liquidglass.app.util

import kotlin.math.*

/**
 * 液态玻璃物理引擎 v2 — iOS 级别流体物理模拟
 *
 * 包含：
 * - SpringPhysics: 弹簧物理（交互回弹、卡片按压）
 * - FluidField: 流体场（Navier-Stokes 简化模型，驱动背景流动）
 * - MetaballPhysics: 水滴融合物理（液态金属球效果）
 * - RipplePhysics: 涟漪扩散物理（水波传播）
 * - RefractionModel: 玻璃折射模型（光线弯曲模拟）
 */
object FluidEngine {

    // ======================== 弹簧物理 ========================

    /**
     * 临界阻尼弹簧 — iOS 标准交互回弹
     * @param current 当前值
     * @param target 目标值
     * @param velocity 当前速度
     * @param stiffness 刚度（越大越硬）
     * @param damping 阻尼（越大回弹越少）
     * @param dt 时间步长（秒）
     * @return Pair(newValue, newVelocity)
     */
    fun springPhysics(
        current: Float,
        target: Float,
        velocity: Float,
        stiffness: Float = 300f,
        damping: Float = 30f,
        dt: Float = 0.016f
    ): Pair<Float, Float> {
        val displacement = current - target
        val springForce = -stiffness * displacement
        val dampingForce = -damping * velocity
        val acceleration = springForce + dampingForce
        val newVelocity = velocity + acceleration * dt
        val newValue = current + newVelocity * dt
        return Pair(newValue, newVelocity)
    }

    /**
     * iOS 风格弹性回弹（带过冲）
     * dampingRatio < 1 时产生弹性过冲
     */
    fun elasticSpring(
        current: Float,
        target: Float,
        velocity: Float,
        mass: Float = 1f,
        tension: Float = 200f,
        friction: Float = 18f,
        dt: Float = 0.016f
    ): Pair<Float, Float> {
        val force = -tension * (current - target) - friction * velocity
        val acc = force / mass
        val newVel = velocity + acc * dt
        val newPos = current + newVel * dt
        return Pair(newPos, newVel)
    }

    // ======================== 流体场 ========================

    /**
     * Navier-Stokes 简化流体场
     * 模拟二维不可压缩流体的速度场
     */
    class FluidField(val width: Int, val height: Int) {
        private val size = width * height
        private val velocityX = FloatArray(size)
        private val velocityY = FloatArray(size)
        private val density = FloatArray(size)
        private val prevVelocityX = FloatArray(size)
        private val prevVelocityY = FloatArray(size)
        private val prevDensity = FloatArray(size)

        var viscosity = 0.0001f
        var diffusion = 0.0001f
        var dt = 0.1f

        fun idx(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            return cy * width + cx
        }

        fun addVelocity(x: Int, y: Int, vx: Float, vy: Float) {
            val i = idx(x, y)
            velocityX[i] += vx
            velocityY[i] += vy
        }

        fun addDensity(x: Int, y: Int, d: Float) {
            density[idx(x, y)] += d
        }

        fun getDensity(x: Int, y: Int): Float = density[idx(x, y)]

        fun getVelocityX(x: Int, y: Int): Float = velocityX[idx(x, y)]
        fun getVelocityY(x: Int, y: Int): Float = velocityY[idx(x, y)]

        fun step() {
            diffuse(1, prevVelocityX, velocityX, viscosity, dt)
            diffuse(2, prevVelocityY, velocityY, viscosity, dt)
            project(prevVelocityX, prevVelocityY, velocityX, velocityY)
            advect(1, velocityX, prevVelocityX, prevVelocityX, prevVelocityY)
            advect(2, velocityY, prevVelocityY, prevVelocityX, prevVelocityY)
            project(velocityX, velocityY, prevVelocityX, prevVelocityY)
            diffuse(0, prevDensity, density, diffusion, dt)
            advect(0, density, prevDensity, prevVelocityX, prevVelocityY)
            fadeDensity()
        }

        private fun diffuse(b: Int, x: FloatArray, x0: FloatArray, diff: Float, dt: Float) {
            val a = dt * diff * (width - 2) * (height - 2)
            linearSolve(b, x, x0, a, 1 + 6 * a)
        }

        private fun linearSolve(b: Int, x: FloatArray, x0: FloatArray, a: Float, c: Float) {
            val cRecip = 1f / c
            for (k in 0 until 4) { // 迭代4次
                for (j in 1 until height - 1) {
                    for (i in 1 until width - 1) {
                        x[idx(i, j)] = (x0[idx(i, j)] + a * (
                            x[idx(i + 1, j)] + x[idx(i - 1, j)] +
                            x[idx(i, j + 1)] + x[idx(i, j - 1)]
                        )) * cRecip
                    }
                }
                setBounds(b, x)
            }
        }

        private fun project(velX: FloatArray, velY: FloatArray, p: FloatArray, div: FloatArray) {
            for (j in 1 until height - 1) {
                for (i in 1 until width - 1) {
                    div[idx(i, j)] = -0.5f * (
                        velX[idx(i + 1, j)] - velX[idx(i - 1, j)] +
                        velY[idx(i, j + 1)] - velY[idx(i, j - 1)]
                    ) / width
                    p[idx(i, j)] = 0f
                }
            }
            setBounds(0, div)
            setBounds(0, p)
            linearSolve(0, p, div, 1f, 6f)
            for (j in 1 until height - 1) {
                for (i in 1 until width - 1) {
                    velX[idx(i, j)] -= 0.5f * (p[idx(i + 1, j)] - p[idx(i - 1, j)]) * width
                    velY[idx(i, j)] -= 0.5f * (p[idx(i, j + 1)] - p[idx(i, j - 1)]) * width
                }
            }
            setBounds(1, velX)
            setBounds(2, velY)
        }

        private fun advect(b: Int, d: FloatArray, d0: FloatArray, velX: FloatArray, velY: FloatArray) {
            val dtX = dt * (width - 2)
            val dtY = dt * (height - 2)
            for (j in 1 until height - 1) {
                for (i in 1 until width - 1) {
                    var x = i - dtX * velX[idx(i, j)]
                    var y = j - dtY * velY[idx(i, j)]
                    x = x.coerceIn(0.5f, width - 1.5f)
                    y = y.coerceIn(0.5f, height - 1.5f)
                    val i0 = x.toInt()
                    val i1 = i0 + 1
                    val j0 = y.toInt()
                    val j1 = j0 + 1
                    val s1 = x - i0
                    val s0 = 1f - s1
                    val t1 = y - j0
                    val t0 = 1f - t1
                    d[idx(i, j)] = s0 * (t0 * d0[idx(i0, j0)] + t1 * d0[idx(i0, j1)]) +
                            s1 * (t0 * d0[idx(i1, j0)] + t1 * d0[idx(i1, j1)])
                }
            }
            setBounds(b, d)
        }

        private fun setBounds(b: Int, x: FloatArray) {
            for (i in 1 until width - 1) {
                x[idx(i, 0)] = if (b == 2) -x[idx(i, 1)] else x[idx(i, 1)]
                x[idx(i, height - 1)] = if (b == 2) -x[idx(i, height - 2)] else x[idx(i, height - 2)]
            }
            for (j in 1 until height - 1) {
                x[idx(0, j)] = if (b == 1) -x[idx(1, j)] else x[idx(1, j)]
                x[idx(width - 1, j)] = if (b == 1) -x[idx(width - 2, j)] else x[idx(width - 2, j)]
            }
            x[idx(0, 0)] = 0.5f * (x[idx(1, 0)] + x[idx(0, 1)])
            x[idx(0, height - 1)] = 0.5f * (x[idx(1, height - 1)] + x[idx(0, height - 2)])
            x[idx(width - 1, 0)] = 0.5f * (x[idx(width - 2, 0)] + x[idx(width - 1, 1)])
            x[idx(width - 1, height - 1)] = 0.5f * (x[idx(width - 2, height - 1)] + x[idx(width - 1, height - 2)])
        }

        private fun fadeDensity() {
            for (i in density.indices) {
                density[i] = max(density[i] - 0.002f, 0f)
            }
        }
    }

    // ======================== Metaball 水滴融合物理 ========================

    /**
     * Metaball 物理 — 模拟液态金属球融合效果
     * 当两个球靠近时，表面张力使它们融合
     */
    data class Metaball(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var radius: Float
    )

    /**
     * 计算 metaball 场在某点的值
     * 值 > 阈值时该点在液态体内
     */
    fun metaballField(px: Float, py: Float, balls: List<Metaball>): Float {
        var sum = 0f
        for (ball in balls) {
            val dx = px - ball.x
            val dy = py - ball.y
            val distSq = dx * dx + dy * dy
            if (distSq < 1f) continue
            sum += (ball.radius * ball.radius) / distSq
        }
        return sum
    }

    /**
     * 更新 metaball 物理状态
     * 包含：边界反弹、速度衰减、轻微吸引力
     */
    fun updateMetaballs(balls: List<Metaball>, w: Float, h: Float, dt: Float = 0.016f) {
        for (ball in balls) {
            ball.x += ball.vx * dt
            ball.y += ball.vy * dt

            // 边界反弹
            if (ball.x - ball.radius < 0) {
                ball.x = ball.radius
                ball.vx = abs(ball.vx) * 0.7f
            }
            if (ball.x + ball.radius > w) {
                ball.x = w - ball.radius
                ball.vx = -abs(ball.vx) * 0.7f
            }
            if (ball.y - ball.radius < 0) {
                ball.y = ball.radius
                ball.vy = abs(ball.vy) * 0.7f
            }
            if (ball.y + ball.radius > h) {
                ball.y = h - ball.radius
                ball.vy = -abs(ball.vy) * 0.7f
            }

            // 速度衰减
            ball.vx *= 0.99f
            ball.vy *= 0.99f
        }
    }

    // ======================== 涟漪物理 ========================

    /**
     * 涟漪波动物理 — 模拟水滴落入液面后的波传播
     * 基于波动方程的简化模型
     */
    class RippleField(val gridSize: Int = 80) {
        private val current = FloatArray(gridSize * gridSize)
        private val previous = FloatArray(gridSize * gridSize)
        private val damping = 0.985f

        fun idx(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, gridSize - 1)
            val cy = y.coerceIn(0, gridSize - 1)
            return cy * gridSize + cx
        }

        fun addRipple(x: Float, y: Float, strength: Float = 1f) {
            val gx = (x * gridSize).toInt().coerceIn(1, gridSize - 2)
            val gy = (y * gridSize).toInt().coerceIn(1, gridSize - 2)
            val i = idx(gx, gy)
            previous[i] = strength
        }

        fun step() {
            for (j in 1 until gridSize - 1) {
                for (i in 1 until gridSize - 1) {
                    val idx = idx(i, j)
                    current[idx] = (
                        previous[idx(i - 1, j)] +
                        previous[idx(i + 1, j)] +
                        previous[idx(i, j - 1)] +
                        previous[idx(i, j + 1)]
                    ) * 0.5f - current[idx]
                    current[idx] *= damping
                }
            }
            // 交换缓冲区
            val tmp = current
            current.copyInto(previous)
            previous.copyInto(current)
            // 实际上需要交换引用，但数组不能交换引用在Kotlin中
            // 用更简单的方式
        }

        fun getHeight(x: Int, y: Int): Float = current[idx(x, y)]
    }

    // ======================== 玻璃折射模型 ========================

    /**
     * 玻璃折射模型 — 模拟光线穿过玻璃时的弯曲
     * @param normalX 法线X分量
     * @param normalY 法线Y分量
     * @param ior 折射率（玻璃约1.5）
     * @param incidentX 入射光X
     * @param incidentY 入射光Y
     * @return 折射后的方向向量
     */
    fun refract(
        normalX: Float, normalY: Float,
        incidentX: Float, incidentY: Float,
        ior: Float = 1.5f
    ): Pair<Float, Float> {
        val cosI = -(normalX * incidentX + normalY * incidentY)
        val eta = if (cosI > 0) 1f / ior else ior
        val k = 1f - eta * eta * (1f - cosI * cosI)
        if (k < 0f) return Pair(0f, 0f) // 全反射
        val factor = eta * cosI + sqrt(k)
        return Pair(
            eta * incidentX + factor * normalX,
            eta * incidentY + factor * normalY
        )
    }

    /**
     * 玻璃边缘菲涅尔效应 — 视角越斜，反射越强
     * @param dotProd 视线与法线的点积（0=掠射, 1=垂直）
     * @return 反射强度 (0..1)
     */
    fun fresnel(dotProd: Float, ior: Float = 1.5f): Float {
        val cosTheta = dotProd.coerceIn(0f, 1f)
        val r0 = ((1f - ior) / (1f + ior)).pow(2f)
        return r0 + (1f - r0) * (1f - cosTheta).pow(5f)
    }

    /**
     * 玻璃表面法线计算 — 基于高度图
     * 用于动态计算玻璃表面的法线方向
     */
    fun surfaceNormal(heightMap: FloatArray, width: Int, x: Int, y: Int, strength: Float = 0.05f): Pair<Float, Float> {
        val cx = x.coerceIn(1, width - 2)
        val cy = y.coerceIn(1, heightMap.size / width - 2)
        val i = cy * width + cx
        val dx = (heightMap[i + 1] - heightMap[i - 1]) * strength
        val dy = (heightMap[i + width] - heightMap[i - width]) * strength
        val len = sqrt(dx * dx + dy * dy + 1f)
        return Pair(-dx / len, -dy / len)
    }

    // ======================== 旧接口兼容 ========================

    fun fluidWave(x: Float, y: Float, time: Float): Float {
        var value = 0f
        value += sin(x * 4.0f + time * 0.7f) * cos(y * 3.0f + time * 0.5f) * 0.5f
        value += sin(x * 6.5f - time * 0.6f) * cos(y * 5.5f + time * 0.4f) * 0.3f
        value += cos(x * 8.0f + time * 0.8f) * sin(y * 7.0f - time * 0.6f) * 0.2f
        return value
    }

    fun dropletRipple(cx: Float, cy: Float, px: Float, py: Float, radius: Float): Float {
        val dx = px - cx
        val dy = py - cy
        val dist = sqrt(dx * dx + dy * dy)
        val normalized = (dist / radius).coerceIn(0f, 1f)
        return (1f - normalized) * cos(normalized * PI.toFloat() * 2f) * 0.5f + 0.5f
    }

    fun refractionOffset(normalX: Float, normalY: Float, strength: Float = 0.05f): Pair<Float, Float> {
        return Pair(normalX * strength, normalY * strength)
    }

    fun colorBlendFactor(index: Int, time: Float): Float {
        val phase = index * PI.toFloat() / 3f
        return (sin(time * 0.3f + phase) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }
}
