package com.lyes.vrclaude

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    fun floatBuffer(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program link failed: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    fun textTexture(text: String, bg: Int, fg: Int, w: Int = 512, h: Int = 128): Int {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bg
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.3f, h * 0.3f, paint)
        paint.color = fg
        paint.textSize = h * 0.42f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, w / 2f, y, paint)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return t[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    // ---- shaders ----

    // Passthrough: the camera image on a world-space quad where the camera was looking
    // when it took the picture, so head rotation stays smooth between camera frames.
    const val BG_VS = """
uniform mat4 uVP;
attribute vec3 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() { vUv = aUv; gl_Position = uVP * vec4(aPos, 1.0); }
"""
    const val BG_FS = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

    const val COLOR_VS = """
uniform mat4 uVP;
uniform mat4 uModel;
uniform float uPointScale;
attribute vec3 aPos;
attribute vec3 aNormal;
varying vec3 vN;
varying vec3 vW;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vW = w.xyz;
  vN = (uModel * vec4(aNormal, 0.0)).xyz;
  gl_Position = uVP * w;
  gl_PointSize = max(2.0, uPointScale / max(gl_Position.w, 0.05));
}
"""
    const val COLOR_FS = """
precision mediump float;
uniform vec4 uColor;
uniform float uLit;
uniform float uChecker;
uniform float uRound;
varying vec3 vN;
varying vec3 vW;
void main() {
  if (uRound > 0.5) {
    vec2 d = gl_PointCoord - vec2(0.5);
    if (dot(d, d) > 0.25) discard;
  }
  vec4 c = uColor;
  if (uChecker > 0.5) {
    float k = mod(floor(vW.x * 2.0) + floor(vW.z * 2.0), 2.0);
    c.rgb *= (k < 0.5 ? 1.0 : 0.55);
  }
  if (uLit > 0.5) {
    vec3 n = normalize(vN);
    float d = max(dot(n, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
    c.rgb *= 0.45 + 0.55 * d;
  }
  gl_FragColor = c;
}
"""

    const val TEX_VS = """
uniform mat4 uMVP;
attribute vec3 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() { vUv = aUv; gl_Position = uMVP * vec4(aPos, 1.0); }
"""
    const val TEX_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform float uHighlight;
varying vec2 vUv;
void main() {
  vec4 c = texture2D(uTex, vUv);
  c.rgb += uHighlight * 0.25;
  gl_FragColor = c;
}
"""

    // Barrel pre-distortion that cancels the viewer lens's pincushion distortion.
    const val DIST_VS = """
attribute vec2 aPos;
varying vec2 vP;
void main() { vP = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    const val DIST_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uAspect;
uniform vec2 uK;
uniform float uScale;
uniform float uCenter;
varying vec2 vP;
void main() {
  vec2 c = vec2(uCenter, 0.0);
  vec2 p = (vP - c) * uAspect;
  float r2 = dot(p, p);
  float f = (1.0 + uK.x * r2 + uK.y * r2 * r2) / uScale;
  vec2 q = p * f / uAspect + c;
  vec2 uv = q * 0.5 + 0.5;
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  } else {
    gl_FragColor = texture2D(uTex, uv);
  }
}
"""
}
