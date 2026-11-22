package io.legado.app.ui.book.read.page.delegate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 基于 TextureView + OpenGL ES 2.0 3D 圆柱体网格卷曲的仿真翻页实现（v6 跟手阻尼曲线 + 三区差异化折角版）。
 *
 * 优化内容：
 * 1. 跟手 + 渐进阻尼曲线：前半段（手指滑过前 50%）卷轴基本 1:1 紧跟手指；后半段靠近装订边时按 tanh(1.08*u)
 *    平滑减速（滑到对侧边缘时翻过约 79%，告别旧版仅翻 1/3 的迟滞感），松手后丝滑续接完成翻页；
 * 2. 上/中/下三区明显差异化折角：移除原版强行锁死 touchY 的限制，传入真实 startY/touchY——
 *    中部 1/3 为干脆的竖直平翻，上部 1/3 为右上角向下斜掀，下部 1/3 为右下角向上斜掀（并随手指上下微调角度），
 *    同时通过精确的书脊几何约束自动收直倾角，保证左侧装订边绝不脱落或上下对折；
 * 3. 保留 v5 的零回闪状态机（animFinished + 首帧 alpha 门控 + 提前收尾缓冲）。
 */
class SimulationPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private var mCornerX = 1
    private var mCornerY = 1

    private var curBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private val screenshotCanvas = Canvas()

    private val glView: PageCurlTextureView = PageCurlTextureView(readView.context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        alpha = 0f
        visibility = View.INVISIBLE
    }

    private var glActive = false
    private var animFinished = false

    init {
        readView.addView(glView)
    }

    override fun setBitmap() {
        animFinished = false
        when (mDirection) {
            PageDirection.PREV -> {
                prevBitmap = prevPage.screenshot(prevBitmap, screenshotCanvas)
                curBitmap = curPage.screenshot(curBitmap, screenshotCanvas)
                glView.setPages(prevBitmap, curBitmap, ReadBookConfig.bgMeanColor)
            }

            PageDirection.NEXT -> {
                nextBitmap = nextPage.screenshot(nextBitmap, screenshotCanvas)
                curBitmap = curPage.screenshot(curBitmap, screenshotCanvas)
                glView.setPages(curBitmap, nextBitmap, ReadBookConfig.bgMeanColor)
            }

            else -> Unit
        }
    }

    override fun setViewSize(width: Int, height: Int) {
        super.setViewSize(width, height)
    }

    override fun onTouch(event: MotionEvent) {
        super.onTouch(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animFinished = false
                calcCornerXY(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                animFinished = false
                // 保留真实的 touchY，不再强行改写成顶部/底部，从而真实区分上/中/下三区翻页效果
            }
        }
    }

    override fun setDirection(direction: PageDirection) {
        super.setDirection(direction)
        when (direction) {
            PageDirection.PREV -> {
                calcCornerXY(viewWidth.toFloat(), startY)
            }

            PageDirection.NEXT -> {
                calcCornerXY(viewWidth.toFloat(), startY)
            }

            else -> Unit
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        animFinished = false
        var dx: Float
        val dy: Float
        if (isCancel) {
            dx = if (mCornerX > 0 && mDirection == PageDirection.NEXT) {
                (viewWidth - touchX)
            } else {
                -touchX
            }
            if (mDirection != PageDirection.NEXT) {
                dx = -(viewWidth + touchX)
            }
            dy = if (mCornerY > 0) {
                (viewHeight - touchY)
            } else {
                -touchY
            }
        } else {
            dx = if (mCornerX > 0 && mDirection == PageDirection.NEXT) {
                -(viewWidth + touchX)
            } else {
                viewWidth - touchX
            }
            dy = if (mCornerY > 0) {
                (viewHeight - touchY)
            } else {
                (1 - touchY)
            }
        }
        startScroll(touchX.toInt(), touchY.toInt(), dx.toInt(), dy.toInt(), animationSpeed)
    }

    override fun onAnimStop() {
        animFinished = true
        hideGL()
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning || animFinished) {
            hideGL()
            return
        }
        if (mDirection != PageDirection.NEXT && mDirection != PageDirection.PREV) {
            hideGL()
            return
        }
        glView.updateCurl(
            touchX = touchX,
            touchY = touchY,
            startY = startY,
            viewW = viewWidth.toFloat(),
            viewH = viewHeight.toFloat()
        )
        showGL()
    }

    override fun onDestroy() {
        super.onDestroy()
        animFinished = true
        hideGL()
        glView.release()
        readView.removeView(glView)
        curBitmap?.recycle()
        prevBitmap?.recycle()
        nextBitmap?.recycle()
        curBitmap = null
        prevBitmap = null
        nextBitmap = null
    }

    private fun showGL() {
        if (!glActive) {
            glActive = true
            glView.bringToFront()
            glView.visibility = View.VISIBLE
            glView.alpha = if (glView.hasFreshFrame) 1f else 0f
        } else if (glView.hasFreshFrame && glView.alpha < 1f) {
            glView.alpha = 1f
        }
    }

    private fun hideGL() {
        if (glActive || glView.visibility == View.VISIBLE || glView.alpha > 0f) {
            glActive = false
            glView.alpha = 0f
            glView.visibility = View.INVISIBLE
        }
    }

    private fun calcCornerXY(x: Float, y: Float) {
        mCornerX = viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
    }

    private class PageCurlTextureView(context: Context) : TextureView(context),
        TextureView.SurfaceTextureListener {

        private val cols = 64
        private val rows = 48
        private val vertexCount = (cols + 1) * (rows + 1)
        private val indexCount = cols * rows * 6

        // 每个顶点: x, y, u, v, dist (5 floats)
        private val meshFloats = FloatArray(vertexCount * 5)
        private val meshBuffer: FloatBuffer = ByteBuffer.allocateDirect(meshFloats.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indexCount * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()

        private val bgQuad = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        private val bgBuffer: FloatBuffer = ByteBuffer.allocateDirect(bgQuad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(bgQuad)
                position(0)
            }

        private val lock = Any()
        @Volatile private var pendingTop: Bitmap? = null
        @Volatile private var pendingBottom: Bitmap? = null
        @Volatile private var dirtyTextures = false
        @Volatile private var renderQueued = false
        @Volatile private var flipGeneration = 0
        @Volatile var hasFreshFrame = false
            private set

        private var bgR = 0.94f
        private var bgG = 0.91f
        private var bgB = 0.85f

        private var curlTouchX = 0f
        private var curlTouchY = 0f
        private var curlStartY = 0f
        private var curlW = 1f
        private var curlH = 1f
        private var axisAx = 0f
        private var axisAy = 0f
        private var axisNx = 1f
        private var axisNy = 0f
        private var curlRadius = 0.08f
        private var shadowAlpha = 0.16f

        private var surfaceW = 1
        private var surfaceH = 1

        private val glThread = HandlerThread("LegadoPageCurlGL").apply { start() }
        private val glHandler = Handler(glThread.looper)

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var glReady = false

        private var texTop = 0
        private var texBottom = 0
        private var frontProgram = 0
        private var bgProgram = 0

        private val drawRunnable = Runnable {
            renderQueued = false
            drawFrameOnGL()
        }

        init {
            isOpaque = false
            surfaceTextureListener = this

            val indices = ShortArray(indexCount)
            var ptr = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i0 = (r * (cols + 1) + c).toShort()
                    val i1 = (i0 + 1).toShort()
                    val i2 = (i0 + cols + 1).toShort()
                    val i3 = (i2 + 1).toShort()
                    indices[ptr++] = i0
                    indices[ptr++] = i2
                    indices[ptr++] = i1
                    indices[ptr++] = i1
                    indices[ptr++] = i2
                    indices[ptr++] = i3
                }
            }
            indexBuffer.put(indices)
            indexBuffer.position(0)
        }

        fun setPages(top: Bitmap?, bottom: Bitmap?, paperColor: Int) {
            synchronized(lock) {
                pendingTop = top
                pendingBottom = bottom
                dirtyTextures = true
                flipGeneration++
                hasFreshFrame = false
                bgR = Color.red(paperColor) / 255f
                bgG = Color.green(paperColor) / 255f
                bgB = Color.blue(paperColor) / 255f
            }
        }

        fun updateCurl(
            touchX: Float,
            touchY: Float,
            startY: Float,
            viewW: Float,
            viewH: Float
        ) {
            synchronized(lock) {
                curlTouchX = touchX
                curlTouchY = touchY
                curlStartY = startY
                curlW = max(viewW, 1f)
                curlH = max(viewH, 1f)
            }
            scheduleRender()
        }

        fun release() {
            glHandler.post {
                destroyEGL()
                glThread.quitSafely()
            }
        }

        private fun scheduleRender() {
            if (!renderQueued && glThread.isAlive) {
                renderQueued = true
                glHandler.post(drawRunnable)
            }
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceW = max(width, 1)
            surfaceH = max(height, 1)
            glHandler.post {
                initEGL(surface)
                if (pendingTop != null && pendingBottom != null) {
                    drawFrameOnGL()
                }
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceW = max(width, 1)
            surfaceH = max(height, 1)
            glHandler.post {
                if (glReady) {
                    GLES20.glViewport(0, 0, surfaceW, surfaceH)
                    if (pendingTop != null && pendingBottom != null) {
                        drawFrameOnGL()
                    }
                }
            }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            glHandler.post { destroyEGL() }
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

        private fun initEGL(surfaceTexture: SurfaceTexture) {
            destroyEGL()
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                return
            }
            val config = configs[0] ?: return

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surfaceTexture, surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            bgProgram = buildProgram(BG_VERTEX_SHADER, BG_FRAGMENT_SHADER)
            frontProgram = buildProgram(CURL_VERTEX_SHADER, CURL_FRAGMENT_SHADER)

            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            texTop = texIds[0]
            texBottom = texIds[1]
            setupTexture(texTop)
            setupTexture(texBottom)

            dirtyTextures = true
            glReady = true
        }

        private fun destroyEGL() {
            glReady = false
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun drawFrameOnGL() {
            if (!glReady) return

            val topBmp: Bitmap?
            val bottomBmp: Bitmap?
            val needUpload: Boolean
            val gen: Int
            val tx: Float
            val ty: Float
            val sy: Float
            val w: Float
            val h: Float
            val pr: Float
            val pg: Float
            val pb: Float

            synchronized(lock) {
                topBmp = pendingTop
                bottomBmp = pendingBottom
                needUpload = dirtyTextures
                dirtyTextures = false
                gen = flipGeneration
                tx = curlTouchX
                ty = curlTouchY
                sy = curlStartY
                w = curlW
                h = curlH
                pr = bgR
                pg = bgG
                pb = bgB
            }

            if (needUpload) {
                if (topBmp != null && !topBmp.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, topBmp, 0)
                }
                if (bottomBmp != null && !bottomBmp.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBottom)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bottomBmp, 0)
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (topBmp == null || bottomBmp == null) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                return
            }

            val aspect = w / h
            buildCylinderMesh(tx, ty, sy, aspect)

            // Pass 1：底层下一页 + 柔和外投影
            GLES20.glUseProgram(bgProgram)
            val bgPosLoc = GLES20.glGetAttribLocation(bgProgram, "aPos")
            val bgUvLoc = GLES20.glGetAttribLocation(bgProgram, "aUv")
            bgBuffer.position(0)
            GLES20.glEnableVertexAttribArray(bgPosLoc)
            GLES20.glVertexAttribPointer(bgPosLoc, 2, GLES20.GL_FLOAT, false, 16, bgBuffer)
            bgBuffer.position(2)
            GLES20.glEnableVertexAttribArray(bgUvLoc)
            GLES20.glVertexAttribPointer(bgUvLoc, 2, GLES20.GL_FLOAT, false, 16, bgBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBottom)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram, "uTex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(bgProgram, "uAxisA"), axisAx, axisAy)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(bgProgram, "uAxisN"), axisNx, axisNy)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uAspect"), aspect)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uRadius"), curlRadius)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProgram, "uShadowAlpha"), shadowAlpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(bgPosLoc)
            GLES20.glDisableVertexAttribArray(bgUvLoc)

            // Pass 2 & Pass 3：仅当卷页尚未完全移出左边界时绘制上层书页
            if (axisAx + curlRadius > -0.02f) {
                GLES20.glUseProgram(frontProgram)
                val stride = 5 * 4
                val posLoc = GLES20.glGetAttribLocation(frontProgram, "aPos")
                val uvLoc = GLES20.glGetAttribLocation(frontProgram, "aUv")
                val distLoc = GLES20.glGetAttribLocation(frontProgram, "aDist")
                val passLoc = GLES20.glGetUniformLocation(frontProgram, "uPass")

                meshBuffer.position(0)
                GLES20.glEnableVertexAttribArray(posLoc)
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, meshBuffer)

                meshBuffer.position(2)
                GLES20.glEnableVertexAttribArray(uvLoc)
                GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, meshBuffer)

                meshBuffer.position(4)
                GLES20.glEnableVertexAttribArray(distLoc)
                GLES20.glVertexAttribPointer(distLoc, 1, GLES20.GL_FLOAT, false, stride, meshBuffer)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(frontProgram, "uTex"), 0)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(frontProgram, "uPaperColor"), pr, pg, pb)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(frontProgram, "uRadius"), curlRadius)

                GLES20.glUniform1i(passLoc, 0)
                indexBuffer.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

                GLES20.glUniform1i(passLoc, 1)
                indexBuffer.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

                GLES20.glDisableVertexAttribArray(posLoc)
                GLES20.glDisableVertexAttribArray(uvLoc)
                GLES20.glDisableVertexAttribArray(distLoc)
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            if (!hasFreshFrame && gen == flipGeneration) {
                hasFreshFrame = true
                post {
                    if (visibility == View.VISIBLE && hasFreshFrame) {
                        alpha = 1f
                    }
                }
            }
        }

        private fun buildCylinderMesh(
            touchX: Float,
            touchY: Float,
            startY: Float,
            aspect: Float
        ) {
            val tx = touchX / curlH
            val ty = min(max(1f - touchY / curlH, 0f), 1f)
            val sy = min(max(1f - startY / curlH, 0f), 1f)
            val cx = aspect

            // 1. 翻页进度 u：手指在右边缘为 0，滑到左边缘为 1，松手退出动画结束时为 2
            val uDrag = min(max((cx - tx) / cx, 0f), 2f)
            val leftProgress = min(max(uDrag * 0.5f, 0f), 1f)

            // 2. 动态圆柱半径：起止收窄、中段舒展
            val r = 0.045f + 0.055f * sin(leftProgress * PI).toFloat()
            val halfCirc = (PI * r).toFloat()

            // 3. 跟手 + 渐进阻尼曲线（tanh）：
            //    - 前半段（uDrag 0 -> 0.5）：斜率接近 1.0，卷页紧跟手指移动；
            //    - 后半段（uDrag 0.5 -> 1.0）：受左侧书脊牵拉，平滑减速至约 79% 处；
            //    - 退出段（tx < 0）：从 79% 无缝衔接，提前在 tx = -0.82*aspect 处完全移出左边界。
            val dampK = 1.08
            val pAtEdge = tanh(dampK).toFloat()
            val ax: Float
            if (tx >= 0f) {
                val p = tanh(dampK * min(uDrag, 1f).toDouble()).toFloat()
                val baseAx = cx * (1f - p)
                val rightGap = cx - tx
                ax = if (rightGap < r * 0.8f) {
                    val uRight = 1f - min(max(rightGap / (r * 0.8f), 0f), 1f)
                    baseAx + ((cx + r * 0.35f) - baseAx) * uRight
                } else {
                    baseAx
                }
            } else {
                val uExit = min(max(-tx / (cx * 0.82f), 0f), 1f)
                val startAx = cx * (1f - pAtEdge)
                val endAx = -(r * 2.6f)
                ax = startAx + (endAx - startAx) * uExit
            }

            // 4. 上/中/下三区差异化折角 + 书脊防脱落几何约束：
            //    - 中部（sy ∈ [0.33, 0.67]）：干脆竖直平翻（仅随手指上下有极轻微倾斜）；
            //    - 下部（sy < 0.33）：右下角向上斜掀（基础约 -30°，向上滑可增至 ~43°）；
            //    - 上部（sy > 0.67）：右上角向下斜掀（基础约 +30°，向下滑可增至 ~43°）；
            //    - 随着 ax 靠近左侧书脊，根据精确的书脊不等式自动收直倾角，保证左侧装订边永不脱离或上下对折。
            val ay: Float
            var slope: Float
            when {
                sy in 0.33f..0.67f -> {
                    ay = 0.5f
                    slope = min(max((sy - ty) * 0.25f, -0.08f), 0.08f)
                }

                sy < 0.33f -> {
                    ay = min(max(ty * 0.6f + 0.05f, 0.08f), 0.42f)
                    val desired = -0.58f - max(0f, ty - sy) * 0.9f
                    val safeLimit = max(0f, (ax / max(ay, 0.08f)) * 0.88f)
                    slope = max(-min(0.95f, safeLimit), min(0f, desired))
                }

                else -> {
                    ay = min(max(1f - (1f - ty) * 0.6f - 0.05f, 0.58f), 0.92f)
                    val desired = 0.58f + max(0f, sy - ty) * 0.9f
                    val safeLimit = max(0f, (ax / max(1f - ay, 0.08f)) * 0.88f)
                    slope = min(min(0.95f, safeLimit), max(0f, desired))
                }
            }

            // 贴近最右侧归位时将倾角平滑归零，确保 PREV 完成或取消翻页时页面 100% 展平
            if (tx >= 0f) {
                val rightGap = cx - tx
                if (rightGap < r * 1.4f) {
                    slope *= min(max(rightGap / (r * 1.4f), 0f), 1f)
                }
            } else {
                // 退出阶段随 ax 逼近 0 平滑归零
                slope *= min(max(ax / (cx * 0.25f), 0f), 1f)
            }

            val normLen = max(hypot(1.0, slope.toDouble()).toFloat(), 0.0005f)
            val dx = 1f / normLen
            val dy = slope / normLen

            // 5. 收尾阶段同步将左右两端阴影柔和淡出至 0
            val leftFade = if (ax < r * 0.9f) {
                min(max((ax + r * 1.4f) / (r * 2.3f), 0f), 1f)
            } else {
                1f
            }
            val rightFade = if (ax > cx - r * 0.8f) {
                min(max((cx + r * 0.1f - ax) / (r * 0.9f), 0f), 1f)
            } else {
                1f
            }
            shadowAlpha = 0.16f * leftFade * rightFade

            axisAx = ax
            axisAy = ay
            axisNx = dx
            axisNy = dy
            curlRadius = r

            if (axisAx + curlRadius <= -0.02f) return

            var idx = 0
            for (row in 0..rows) {
                val v = row.toFloat() / rows
                val py = 1f - v
                for (col in 0..cols) {
                    val u = col.toFloat() / cols
                    val px = u * aspect

                    val d = (px - ax) * dx + (py - ay) * dy
                    val outX: Float
                    val outY: Float

                    when {
                        d <= 0f -> {
                            outX = px
                            outY = py
                        }

                        d < halfCirc -> {
                            val theta = d / r
                            val sinT = sin(theta.toDouble()).toFloat()
                            val shift = r * sinT - d
                            outX = px + dx * shift
                            outY = py + dy * shift
                        }

                        else -> {
                            val over = d - halfCirc
                            val shift = -(d + over)
                            outX = px + dx * shift
                            outY = py + dy * shift
                        }
                    }

                    val ndcX = (outX / aspect) * 2f - 1f
                    val ndcY = outY * 2f - 1f

                    meshFloats[idx++] = ndcX
                    meshFloats[idx++] = ndcY
                    meshFloats[idx++] = u
                    meshFloats[idx++] = v
                    meshFloats[idx++] = d
                }
            }

            meshBuffer.position(0)
            meshBuffer.put(meshFloats)
            meshBuffer.position(0)
        }

        private fun setupTexture(texId: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            return shader
        }

        companion object {
            private const val BG_VERTEX_SHADER = """
                attribute vec2 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    vUv = aUv;
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
            """

            private const val BG_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTex;
                uniform vec2 uAxisA;
                uniform vec2 uAxisN;
                uniform float uAspect;
                uniform float uRadius;
                uniform float uShadowAlpha;
                varying vec2 vUv;
                void main() {
                    vec4 col = texture2D(uTex, vUv);
                    vec2 worldP = vec2(vUv.x * uAspect, 1.0 - vUv.y);
                    float d = dot(worldP - uAxisA, uAxisN);
                    float shadowW = max(uRadius * 1.8, 0.04);
                    float s = 1.0;
                    if (d > -0.008 && uShadowAlpha > 0.001) {
                        float fadeIn = smoothstep(-0.008, 0.012, d);
                        float drop = exp(-max(d, 0.0) / shadowW);
                        s = 1.0 - uShadowAlpha * fadeIn * drop;
                    }
                    gl_FragColor = vec4(col.rgb * s, 1.0);
                }
            """

            private const val CURL_VERTEX_SHADER = """
                attribute vec2 aPos;
                attribute vec2 aUv;
                attribute float aDist;
                varying vec2 vUv;
                varying float vDist;
                void main() {
                    vUv = aUv;
                    vDist = aDist;
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
            """

            private const val CURL_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTex;
                uniform vec3 uPaperColor;
                uniform float uRadius;
                uniform int uPass;
                varying vec2 vUv;
                varying float vDist;
                void main() {
                    const float PI = 3.14159265;
                    const float HALF_PI = 1.57079633;
                    float quarterCirc = HALF_PI * uRadius;
                    float halfCirc = PI * uRadius;

                    if (uPass == 0) {
                        if (vDist > quarterCirc) discard;
                        vec4 texCol = texture2D(uTex, vUv);
                        float shade = 1.0;
                        if (vDist > 0.0) {
                            float theta = vDist / uRadius;
                            float c = cos(theta);
                            float s = sin(theta);
                            shade = 0.93 + 0.07 * c + 0.02 * s;
                        }
                        gl_FragColor = vec4(texCol.rgb * shade, 1.0);
                    } else {
                        if (vDist <= quarterCirc) discard;
                        vec4 texCol = texture2D(uTex, vUv);
                        float shade;
                        if (vDist < halfCirc) {
                            float theta = vDist / uRadius;
                            float t = (theta - HALF_PI) / HALF_PI;
                            shade = 0.92 + 0.05 * sin(t * PI);
                        } else {
                            float over = vDist - halfCirc;
                            float foldShadow = exp(-over / max(uRadius * 1.2, 0.035));
                            shade = 0.99 - 0.07 * foldShadow;
                        }
                        vec3 backCol = mix(texCol.rgb, uPaperColor, 0.85);
                        gl_FragColor = vec4(backCol * shade, 1.0);
                    }
                }
            """
        }
    }
}
