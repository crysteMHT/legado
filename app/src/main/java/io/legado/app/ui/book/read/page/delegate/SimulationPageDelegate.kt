package io.legado.app.ui.book.read.page.delegate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import android.widget.FrameLayout
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.screenshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 基于 OpenGL ES 2.0 3D 圆柱体网格卷曲的仿真翻页实现（掌阅/iBooks 风格）。
 * 特点：
 * 1. 零第三方依赖，直接替换 Legado 原生 SimulationPageDelegate.kt 即可编译；
 * 2. 动静分离：静止阅读时将 GL 视图移出屏幕外保持 EGL 上下文常驻（首帧不闪黑，不影响长按选词与菜单）；
 * 3. 3D 圆柱体卷页 + 背面半透明透字 + 内外双层柔和阴影，全程 GPU 加速渲染。
 */
class SimulationPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    private var mCornerX = 1
    private var mCornerY = 1

    private var curBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null
    private val screenshotCanvas = Canvas()

    private val glView: PageCurlGLView = PageCurlGLView(readView.context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        translationX = 10000f
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
        if (!glActive && width > 0) {
            glView.translationX = width * 2f + 100f
        }
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
            glView.translationX = 0f
            glView.bringToFront()
        }
    }

    private fun hideGL() {
        if (glActive) {
            glActive = false
            glView.translationX = max(viewWidth * 2f, 2000f) + 100f
        }
    }

    private fun calcCornerXY(x: Float, y: Float) {
        mCornerX = if (x <= viewWidth / 2) 0 else viewWidth
        mCornerY = if (y <= viewHeight / 2) 0 else viewHeight
    }

    /**
     * 内置 OpenGL ES 2.0 3D 圆柱体卷页渲染视图（无需任何外部库）
     */
    private class PageCurlGLView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

        private val cols = 36
        private val rows = 24
        private val vertexCount = (cols + 1) * (rows + 1)
        private val indexCount = cols * rows * 6

        // 每个顶点: x, y, z, u, v, shade, isBack (7 floats)
        private val meshFloats = FloatArray(vertexCount * 7)
        private val meshBuffer: FloatBuffer = ByteBuffer.allocateDirect(meshFloats.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val indexBuffer: ShortBuffer = ByteBuffer.allocateDirect(indexCount * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()

        // 底层下一页全屏四边形: x, y, u, v (4 floats * 4 vertices)
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

        private var texTop = 0
        private var texBottom = 0
        private var frontProgram = 0
        private var bgProgram = 0

        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)

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

            setRenderer(this)
            renderMode = RENDERMODE_WHEN_DIRTY
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
            requestRender()
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
            requestRender()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
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
            if (topBmp == null || bottomBmp == null) return

            val aspect = w / h
            buildCylinderMesh(tx, ty, cx, cy, w, h, aspect)

            // 1. 绘制底层下一页 + 卷起页投射的柔和外阴影
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

            // 2. 绘制上层 3D 圆柱卷曲书页（正面 + 背面透字 + 曲面明暗）
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glUseProgram(frontProgram)

            val stride = 7 * 4
            val posLoc = GLES20.glGetAttribLocation(frontProgram, "aPos")
            val uvLoc = GLES20.glGetAttribLocation(frontProgram, "aUv")
            val shadeLoc = GLES20.glGetAttribLocation(frontProgram, "aShade")
            val backLoc = GLES20.glGetAttribLocation(frontProgram, "aIsBack")

            meshBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, meshBuffer)

            meshBuffer.position(3)
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, meshBuffer)

            meshBuffer.position(5)
            GLES20.glEnableVertexAttribArray(shadeLoc)
            GLES20.glVertexAttribPointer(shadeLoc, 1, GLES20.GL_FLOAT, false, stride, meshBuffer)

            meshBuffer.position(6)
            GLES20.glEnableVertexAttribArray(backLoc)
            GLES20.glVertexAttribPointer(backLoc, 1, GLES20.GL_FLOAT, false, stride, meshBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texTop)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(frontProgram, "uTex"), 0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(frontProgram, "uPaperColor"), pr, pg, pb)

            indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(uvLoc)
            GLES20.glDisableVertexAttribArray(shadeLoc)
            GLES20.glDisableVertexAttribArray(backLoc)
        }

        private fun buildCylinderMesh(
            touchX: Float,
            touchY: Float,
            cornerX: Float,
            cornerY: Float,
            w: Float,
            h: Float,
            aspect: Float
        ) {
            // 转换到以屏幕高度为 1.0 的世界坐标系: x in [0, aspect], y in [0, 1] (底为0，顶为1)
            val tx = touchX / h
            val ty = 1f - touchY / h
            val cx = if (cornerX <= 0f) 0f else aspect
            val cy = if (cornerY <= 0f) 1f else 0f

            var dx = cx - tx
            var dy = cy - ty
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val safeDist = max(dist, 0.0005f)
            dx /= safeDist
            dy /= safeDist

            // 限制翻页角度不过度竖直，保持自然书页装订手感
            val progress = min(max(dist / (aspect * 2.0f), 0f), 1f)
            // 动态圆柱半径：拉起初期紧致，中段舒展（接近掌阅风格）
            val r = 0.055f + 0.058f * sin(progress * PI).toFloat()
            val halfCirc = (PI * r).toFloat()

            // 折痕轴基准点（位于手指与页角之间，使翻起边缘紧贴手指）
            val ax = (tx + cx) * 0.5f - dx * (r * 0.35f)
            val ay = (ty + cy) * 0.5f - dy * (r * 0.35f)

            axisAx = ax
            axisAy = ay
            axisNx = dx
            axisNy = dy
            curlRadius = r

            var idx = 0
            for (row in 0..rows) {
                val v = row.toFloat() / rows // 0 = top, 1 = bottom
                val py = 1f - v
                for (col in 0..cols) {
                    val u = col.toFloat() / cols
                    val px = u * aspect

                    val d = (px - ax) * dx + (py - ay) * dy
                    var outX: Float
                    var outY: Float
                    var outZ: Float
                    var shade: Float
                    var isBack: Float

                    when {
                        d <= 0f -> {
                            outX = px
                            outY = py
                            outZ = 0f
                            // 靠近卷起折痕处做极柔和的微暗过渡
                            val edgeProx = max(0f, 1f + d / (r * 0.6f))
                            shade = 1f - 0.04f * edgeProx
                            isBack = 0f
                        }

                        d < halfCirc -> {
                            val theta = d / r
                            val sinT = sin(theta.toDouble()).toFloat()
                            val cosT = cos(theta.toDouble()).toFloat()
                            val shift = r * sinT - d
                            outX = px + dx * shift
                            outY = py + dy * shift
                            outZ = r * (1f - cosT)
                            // 圆柱曲面立体明暗：外侧微微提亮，内折处渐变加深
                            val light = 0.80f + 0.22f * sinT
                            shade = min(max(light, 0.72f), 1.03f)
                            isBack = if (theta > (PI * 0.5).toFloat()) 1f else 0f
                        }

                        else -> {
                            val over = d - halfCirc
                            val shift = -(d + over)
                            outX = px + dx * shift
                            outY = py + dy * shift
                            outZ = 2f * r
                            // 背面靠近折痕一侧保留内折阴影
                            val foldDark = max(0f, 1f - over / (r * 1.4f))
                            shade = 0.96f - 0.14f * foldDark
                            isBack = 1f
                        }
                    }

                    // 映射回 OpenGL NDC [-1, 1]，z 越大越靠前（映射到 [-0.9, 0.0] 配合 GL_LEQUAL）
                    val ndcX = (outX / aspect) * 2f - 1f
                    val ndcY = outY * 2f - 1f
                    val ndcZ = -outZ * 2.0f

                    meshFloats[idx++] = ndcX
                    meshFloats[idx++] = ndcY
                    meshFloats[idx++] = ndcZ
                    meshFloats[idx++] = u
                    meshFloats[idx++] = v
                    meshFloats[idx++] = shade
                    meshFloats[idx++] = isBack
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
                precision mediump float;
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
                    float shadowWidth = max(uRadius * 2.2, 0.06);
                    float s = 1.0;
                    if (d > -0.02) {
                        float t = clamp((d + 0.02) / shadowWidth, 0.0, 1.0);
                        s = mix(0.62, 1.0, t * t * (3.0 - 2.0 * t));
                    }
                    gl_FragColor = vec4(col.rgb * s, 1.0);
                }
            """

            private const val CURL_VERTEX_SHADER = """
                attribute vec3 aPos;
                attribute vec2 aUv;
                attribute float aShade;
                attribute float aIsBack;
                varying vec2 vUv;
                varying float vShade;
                varying float vIsBack;
                void main() {
                    vUv = aUv;
                    vShade = aShade;
                    vIsBack = aIsBack;
                    gl_Position = vec4(aPos, 1.0);
                }
            """

            private const val CURL_FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTex;
                uniform vec3 uPaperColor;
                varying vec2 vUv;
                varying float vShade;
                varying float vIsBack;
                void main() {
                    vec4 texCol = texture2D(uTex, vUv);
                    // 背面透字：将镜像后的书页纹理与纸张底色混合（0.72 还原掌阅透印感）
                    vec3 backCol = mix(texCol.rgb, uPaperColor, 0.72);
                    vec3 finalRgb = mix(texCol.rgb, backCol, step(0.5, vIsBack));
                    gl_FragColor = vec4(finalRgb * vShade, 1.0);
                }
            """
        }
    }
}
