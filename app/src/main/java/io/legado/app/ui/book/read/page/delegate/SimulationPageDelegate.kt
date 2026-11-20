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
 * 基于 TextureView + OpenGL ES 2.0 3D 圆柱体网格卷曲的仿真翻页实现（v2 修复版）。
 * 修复内容：
 * 1. 改用 TextureView + EGL14 替代 GLSurfaceView，彻底解决翻页结束后黑屏/遮挡菜单的问题；
 * 2. 加密 3D 网格至 64x48，并将光照、正反面过渡与内外折痕阴影改为片元着色器逐像素连续计算，消除锯齿与生硬灰带。
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
        visibility = View.INVISIBLE
    }

    private var glActive = false

    init {
        readView.addView(glView)
    }

    override fun setBitmap() {
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
                calcCornerXY(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if ((startY > viewHeight / 3 && startY < viewHeight * 2 / 3)
                    || mDirection == PageDirection.PREV
                ) {
                    readView.touchY = viewHeight.toFloat()
                }
                if (startY > viewHeight / 3 && startY < viewHeight / 2
                    && mDirection == PageDirection.NEXT
                ) {
                    readView.touchY = 1f
                }
            }
        }
    }

    override fun setDirection(direction: PageDirection) {
        super.setDirection(direction)
        when (direction) {
            PageDirection.PREV -> {
                if (startX > viewWidth / 2) {
                    calcCornerXY(startX, viewHeight.toFloat())
                } else {
                    calcCornerXY(viewWidth - startX, viewHeight.toFloat())
                }
            }

            PageDirection.NEXT -> {
                if (viewWidth / 2 > startX) {
                    calcCornerXY(viewWidth - startX, startY)
                }
            }

            else -> Unit
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
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
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
        hideGL()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) {
            hideGL()
            return
        }
        if (mDirection != PageDirection.NEXT && mDirection != PageDirection.PREV) {
            hideGL()
            return
        }
        showGL()
        glView.updateCurl(
            touchX = touchX,
            touchY = touchY,
            cornerX = mCornerX.toFloat(),
            cornerY = mCornerY.toFloat(),
            viewW = viewWidth.toFloat(),
            viewH = viewHeight.toFloat()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
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
        }
    }

    private fun hideGL() {
        if (glActive || glView.visibility == View.VISIBLE) {
            glActive = false
            glView.visibility = View.INVISIBLE
        }
    }

    private fun calcCornerXY(x: Float, y: Float) {
        mCornerX = if (x <= viewWidth / 2) 0 else viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
    }

    /**
     * 基于 TextureView + EGL14 的 3D 圆柱体卷页渲染视图。
     * 作为普通 View 参与窗口合成，INVISIBLE 时彻底透明且保持 EGL 上下文存活，绝不黑屏、不闪黑。
     */
    private class PageCurlTextureView(context: Context) : TextureView(context),
        TextureView.SurfaceTextureListener {

        private val cols = 64
        private val rows = 48
        private val vertexCount = (cols + 1) * (rows + 1)
        private val indexCount = cols * rows * 6

        // 每个顶点: x, y, z, u, v, dist (6 floats)
        private val meshFloats = FloatArray(vertexCount * 6)
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

        private var bgR = 0.94f
        private var bgG = 0.91f
        private var bgB = 0.85f

        private var curlTouchX = 0f
        private var curlTouchY = 0f
        private var curlCornerX = 1f
        private var curlCornerY = 1f
        private var curlW = 1f
        private var curlH = 1f
        private var axisAx = 0f
        private var axisAy = 0f
        private var axisNx = 1f
        private var axisNy = 0f
        private var curlRadius = 0.08f

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
                bgR = Color.red(paperColor) / 255f
                bgG = Color.green(paperColor) / 255f
                bgB = Color.blue(paperColor) / 255f
            }
            scheduleRender()
        }

        fun updateCurl(
            touchX: Float,
            touchY: Float,
            cornerX: Float,
            cornerY: Float,
            viewW: Float,
            viewH: Float
        ) {
            synchronized(lock) {
                curlTouchX = touchX
                curlTouchY = touchY
                curlCornerX = cornerX
                curlCornerY = cornerY
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
                drawFrameOnGL()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceW = max(width, 1)
            surfaceH = max(height, 1)
            glHandler.post {
                if (glReady) {
                    GLES20.glViewport(0, 0, surfaceW, surfaceH)
                    drawFrameOnGL()
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
                EGL14.EGL_DEPTH_SIZE, 16,
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
            val tx: Float
            val ty: Float
            val cx: Float
            val cy: Float
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
                tx = curlTouchX
                ty = curlTouchY
                cx = curlCornerX
                cy = curlCornerY
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

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (topBmp == null || bottomBmp == null) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                return
            }

            val aspect = w / h
            buildCylinderMesh(tx, ty, cx, cy, aspect)

            // 1. 底层下一页 + 柔和外投影
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
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
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(bgPosLoc)
            GLES20.glDisableVertexAttribArray(bgUvLoc)

            // 2. 上层 3D 圆柱卷曲书页（逐像素连续光照 + 背面透字）
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glUseProgram(frontProgram)

            val stride = 6 * 4
            val posLoc = GLES20.glGetAttribLocation(frontProgram, "aPos")
            val uvLoc = GLES20.glGetAttribLocation(frontProgram, "aUv")
            val distLoc = GLES20.glGetAttribLocation(frontProgram, "aDist")

            meshBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, meshBuffer)

            meshBuffer.position(3)
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, meshBuffer)

            meshBuffer.position(5)
            GLES20.glEnableVertexAttribArray(distLoc)
            GLES20.glVertexAttribPointer(distLoc, 1, GLES20.GL_FLOAT, false, stride, meshBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(frontProgram, "uTex"), 0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(frontProgram, "uPaperColor"), pr, pg, pb)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(frontProgram, "uRadius"), curlRadius)

            indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(uvLoc)
            GLES20.glDisableVertexAttribArray(distLoc)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun buildCylinderMesh(
            touchX: Float,
            touchY: Float,
            cornerX: Float,
            cornerY: Float,
            aspect: Float
        ) {
            val tx = touchX / curlH
            val ty = 1f - touchY / curlH
            val cx = if (cornerX <= 0f) 0f else aspect
            val cy = if (cornerY <= 0f) 1f else 0f

            var dx = cx - tx
            var dy = cy - ty
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val safeDist = max(dist, 0.0005f)
            dx /= safeDist
            dy /= safeDist

            val progress = min(max(dist / (aspect * 2.0f), 0f), 1f)
            val r = 0.052f + 0.056f * sin(progress * PI).toFloat()
            val halfCirc = (PI * r).toFloat()

            val ax = (tx + cx) * 0.5f - dx * (r * 0.35f)
            val ay = (ty + cy) * 0.5f - dy * (r * 0.35f)

            axisAx = ax
            axisAy = ay
            axisNx = dx
            axisNy = dy
            curlRadius = r

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
                    val outZ: Float

                    when {
                        d <= 0f -> {
                            outX = px
                            outY = py
                            outZ = 0f
                        }

                        d < halfCirc -> {
                            val theta = d / r
                            val sinT = sin(theta.toDouble()).toFloat()
                            val cosT = cos(theta.toDouble()).toFloat()
                            val shift = r * sinT - d
                            outX = px + dx * shift
                            outY = py + dy * shift
                            outZ = r * (1f - cosT)
                        }

                        else -> {
                            val over = d - halfCirc
                            val shift = -(d + over)
                            outX = px + dx * shift
                            outY = py + dy * shift
                            outZ = 2f * r
                        }
                    }

                    val ndcX = (outX / aspect) * 2f - 1f
                    val ndcY = outY * 2f - 1f
                    val ndcZ = -outZ * 2.0f

                    meshFloats[idx++] = ndcX
                    meshFloats[idx++] = ndcY
                    meshFloats[idx++] = ndcZ
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
                    gl_Position = vec4(aPos, 0.5, 1.0);
                }
            """

            private const val BG_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTex;
                uniform vec2 uAxisA;
                uniform vec2 uAxisN;
                uniform float uAspect;
                uniform float uRadius;
                varying vec2 vUv;
                void main() {
                    vec4 col = texture2D(uTex, vUv);
                    vec2 worldP = vec2(vUv.x * uAspect, 1.0 - vUv.y);
                    float d = dot(worldP - uAxisA, uAxisN);
                    float shadowW = max(uRadius * 1.9, 0.045);
                    float s = 1.0;
                    if (d > -0.008) {
                        float fadeIn = smoothstep(-0.008, 0.012, d);
                        float drop = exp(-max(d, 0.0) / shadowW);
                        s = 1.0 - 0.22 * fadeIn * drop;
                    }
                    gl_FragColor = vec4(col.rgb * s, 1.0);
                }
            """

            private const val CURL_VERTEX_SHADER = """
                attribute vec3 aPos;
                attribute vec2 aUv;
                attribute float aDist;
                varying vec2 vUv;
                varying float vDist;
                void main() {
                    vUv = aUv;
                    vDist = aDist;
                    gl_Position = vec4(aPos, 1.0);
                }
            """

            private const val CURL_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTex;
                uniform vec3 uPaperColor;
                uniform float uRadius;
                varying vec2 vUv;
                varying float vDist;
                void main() {
                    const float PI = 3.14159265;
                    const float HALF_PI = 1.57079633;
                    float halfCirc = PI * uRadius;
                    vec4 texCol = texture2D(uTex, vUv);

                    float shade = 1.0;
                    float backMix = 0.0;

                    if (vDist <= 0.0) {
                        shade = 1.0;
                        backMix = 0.0;
                    } else if (vDist < halfCirc) {
                        float theta = vDist / uRadius;
                        if (theta < HALF_PI) {
                            // 正面卷起弧面：在 theta=0 处严格为 1.0（与平面零接缝），平滑微暗至卷边
                            float c = cos(theta);
                            float s = sin(theta);
                            shade = 0.90 + 0.10 * c + 0.025 * s;
                            backMix = 0.0;
                        } else {
                            // 背面卷起弧面：在 theta=PI/2 与 theta=PI 处均平滑衔接至 0.88，中段微亮
                            float t = (theta - HALF_PI) / HALF_PI;
                            backMix = smoothstep(HALF_PI, HALF_PI + 0.08, theta);
                            shade = 0.88 + 0.07 * sin(t * PI);
                        }
                    } else {
                        // 翻折后的背面平整区域：在 over=0 处严格为 0.88（零接缝），向外平滑恢复到 0.98
                        float over = vDist - halfCirc;
                        backMix = 1.0;
                        float foldShadow = exp(-over / max(uRadius * 1.25, 0.035));
                        shade = 0.98 - 0.10 * foldShadow;
                    }

                    // 掌阅风格背面透字：78% 纸张底色 + 22% 镜像原页文字
                    vec3 backCol = mix(texCol.rgb, uPaperColor, 0.78);
                    vec3 finalRgb = mix(texCol.rgb, backCol, backMix);
                    gl_FragColor = vec4(finalRgb * shade, 1.0);
                }
            """
        }
    }
}
