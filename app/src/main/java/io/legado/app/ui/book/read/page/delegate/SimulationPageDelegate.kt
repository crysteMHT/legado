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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 基于 TextureView + OpenGL ES 2.0 3D 圆柱体网格卷曲的仿真翻页实现（v7 复刻参考录屏双圆书脊物理版）。
 *
 * 严格对齐参考录屏（含触摸轨迹球）的物理行为与视觉质感：
 * 1. 真实纸角跟手 + 双圆书脊约束：
 *    - 上部 1/3（右上角掀页）与下部 1/3（右下角掀页）：以「抓取书角 C」与「手指位置 P」的精确垂直平分线为卷轴，
 *      翻起的纸角/纸边精确落于手指位置（100% 跟手）；同时施加「同侧书脊圆（半径=页宽）」与「对角书脊圆（半径=对角线）」
 *      双重物理约束，既保证前半段自由斜掀（可达 50°~60°），又在靠近装订边时自然收直减速、左侧装订边绝不脱离；
 *    - 中部 1/3（水平平翻）：严格锁定竖直卷轴（0° 倾角），翻起纸边精确跟随手指水平位置；
 * 2. 复刻参考录屏的紧致卷筒光影：收紧圆柱半径（0.032~0.050），强化卷筒高光、折痕内阴影与底页投射阴影（0.34），
 *    背面半透镜像文字柔和自然；
 * 3. 完整保留 v5 零回闪状态机（animFinished + 首帧 alpha 门控 + 退出平滑续接与提前缓冲）。
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
        private var curlRadius = 0.042f
        private var shadowAlpha = 0.34f

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

        private fun computeAxisAtNonNegativeX(
            pxIn: Float,
            pyIn: Float,
            sy: Float,
            aspect: Float,
            r: Float
        ): FloatArray {
            val cx = aspect
            val halfCirc = (PI * r).toFloat()

            // 中部 1/3：严格竖直平翻，翻起纸边精确落在手指位置 pxIn
            if (sy in 0.33f..0.67f) {
                val px = min(max(pxIn, 0f), cx)
                val distL = max(cx - px, 0f)
                val shift = min(distL * 0.48f, halfCirc * 0.5f)
                val ax = (cx + px) * 0.5f - shift
                return floatArrayOf(ax, 0.5f, 1f, 0f)
            }

            // 上部 1/3（右上角 cy=1）或 下部 1/3（右下角 cy=0）：双圆书脊约束 + 精确垂直平分线
            val cy = if (sy < 0.33f) 0f else 1f
            var px = min(max(pxIn, 0f), cx)
            var py = min(max(pyIn, 0f), 1f)

            // 约束 1：到同侧书脊角 (0, cy) 的距离 <= 页宽 aspect（保证同侧装订边绝不脱落）
            val d1 = hypot(px.toDouble(), (py - cy).toDouble()).toFloat()
            val maxR1 = aspect * 0.992f
            if (d1 > maxR1 && d1 > 0.0001f) {
                val scale = maxR1 / d1
                px *= scale
                py = cy + (py - cy) * scale
            }

            // 约束 2：到对侧书脊角 (0, 1 - cy) 的距离 <= 对角线长度（保证对侧装订边绝不脱落）
            val oppY = 1f - cy
            val d2 = hypot(px.toDouble(), (py - oppY).toDouble()).toFloat()
            val maxR2 = hypot(aspect.toDouble(), 1.0).toFloat() * 0.992f
            if (d2 > maxR2 && d2 > 0.0001f) {
                val scale = maxR2 / d2
                px *= scale
                py = oppY + (py - oppY) * scale
            }

            val vx = cx - px
            val vy = cy - py
            val distL = hypot(vx.toDouble(), vy.toDouble()).toFloat()
            val dx: Float
            val dy: Float
            if (distL < 0.0005f) {
                dx = 1f
                dy = 0f
            } else {
                dx = vx / distL
                dy = vy / distL
            }

            // 使翻起后的纸角精确落于手指位置 (px, py)
            val shift = min(distL * 0.48f, halfCirc * 0.5f)
            val ax = (cx + px) * 0.5f - dx * shift
            val ay = (cy + py) * 0.5f - dy * shift
            return floatArrayOf(ax, ay, dx, dy)
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

            val leftProgress = min(max((cx - tx) / (cx * 2.0f), 0f), 1f)

            // 紧致纸张卷筒半径（对齐参考录屏质感）
            val r = 0.032f + 0.018f * sin(leftProgress * PI).toFloat()
            val halfCirc = (PI * r).toFloat()

            var ax: Float
            var ay: Float
            var dx: Float
            var dy: Float

            if (tx >= 0f) {
                val res = computeAxisAtNonNegativeX(tx, ty, sy, aspect, r)
                ax = res[0]
                ay = res[1]
                dx = res[2]
                dy = res[3]

                // 贴近最右侧归位（PREV 完成或取消翻页）时平滑展平，避免残留微卷边
                val rightGap = cx - tx
                if (rightGap < r * 1.4f) {
                    val uRight = 1f - min(max(rightGap / (r * 1.4f), 0f), 1f)
                    ax += ((cx + r * 0.4f) - ax) * uRight
                    dy *= (1f - uRight)
                    val nl = max(hypot(dx.toDouble(), dy.toDouble()).toFloat(), 0.0005f)
                    dx /= nl
                    dy /= nl
                }
            } else {
                // 松手退出动画：从 tx=0 处的精确状态无缝续接，提前在 tx = -0.85*aspect 处完全移出左边界
                val res0 = computeAxisAtNonNegativeX(0f, ty, sy, aspect, r)
                val uExit = min(max(-tx / (cx * 0.85f), 0f), 1f)
                val endAx = -(r * 2.6f)
                ax = res0[0] + (endAx - res0[0]) * uExit
                ay = res0[1] + (0.5f - res0[1]) * uExit
                dx = res0[2] + (1f - res0[2]) * uExit
                dy = res0[3] * (1f - uExit)
                val nl = max(hypot(dx.toDouble(), dy.toDouble()).toFloat(), 0.0005f)
                dx /= nl
                dy /= nl
            }

            // 收尾阶段同步将左右两端阴影柔和淡出至 0
            val leftFade = if (ax < r * 1.2f) {
                min(max((ax + r * 1.6f) / (r * 2.8f), 0f), 1f)
            } else {
                1f
            }
            val rightFade = if (ax > cx - r * 1.0f) {
                min(max((cx + r * 0.1f - ax) / (r * 1.1f), 0f), 1f)
            } else {
                1f
            }
            shadowAlpha = 0.34f * leftFade * rightFade

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
                    float d = dot(worldP - uAxisA, uAxisN) - uRadius;
                    float shadowW = max(uRadius * 2.6, 0.06);
                    float s = 1.0;
                    if (d > -0.01 && uShadowAlpha > 0.001) {
                        float fadeIn = smoothstep(-0.01, 0.01, d);
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
                            shade = 0.86 + 0.14 * c;
                        }
                        gl_FragColor = vec4(texCol.rgb * shade, 1.0);
                    } else {
                        if (vDist <= quarterCirc) discard;
                        vec4 texCol = texture2D(uTex, vUv);
                        float shade;
                        if (vDist < halfCirc) {
                            float theta = vDist / uRadius;
                            float t = (theta - HALF_PI) / HALF_PI;
                            shade = 0.84 + 0.18 * sin(t * PI);
                        } else {
                            float over = vDist - halfCirc;
                            float foldShadow = exp(-over / max(uRadius * 1.4, 0.035));
                            shade = 0.98 - 0.14 * foldShadow;
                        }
                        vec3 backCol = mix(texCol.rgb, uPaperColor, 0.84);
                        gl_FragColor = vec4(backCol * shade, 1.0);
                    }
                }
            """
        }
    }
}
