package dev.experimental.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsmlBuilderTest {

    // ── Escape XML ──────────────────────────────────────────────────────────

    @Test
    fun escapesTheFiveXmlCharacters() {
        assertEquals(
            "5 &lt; 6 &amp;&amp; 7 &gt; 3, &quot;comillas&quot;, &apos;apóstrofe&apos;",
            SsmlBuilder.escapeXml("5 < 6 && 7 > 3, \"comillas\", 'apóstrofe'")
        )
    }

    @Test
    fun ampersandIsEscapedFirstToAvoidDoubleEscaping() {
        assertEquals("&amp;lt;", SsmlBuilder.escapeXml("&lt;"))
        assertEquals("AT&amp;T &amp; Co", SsmlBuilder.escapeXml("AT&T & Co"))
    }

    // ── Parámetros firmados ─────────────────────────────────────────────────

    @Test
    fun rateAndPitchAreSignedAndClamped() {
        assertEquals("+0%", SsmlBuilder.signedPercent(0))
        assertEquals("+25%", SsmlBuilder.signedPercent(25))
        assertEquals("-10%", SsmlBuilder.signedPercent(-10))
        assertEquals("+50%", SsmlBuilder.signedPercent(999))   // recortado
        assertEquals("-50Hz", SsmlBuilder.signedHertz(-999))   // recortado
        assertEquals("+0Hz", SsmlBuilder.signedHertz(0))
    }

    // ── Nombre de voz ───────────────────────────────────────────────────────

    @Test
    fun voiceShortNameExpandsToTheLongMicrosoftForm() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)",
            SsmlBuilder.voiceLongName("es-MX-DaliaNeural")
        )
        // Variante regional compuesta: la parte anterior al '-' va a la región.
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-shandong, YunxiangNeural)",
            SsmlBuilder.voiceLongName("zh-CN-shandong-YunxiangNeural")
        )
        // Entradas que no encajan se devuelven tal cual.
        assertEquals("algo-raro", SsmlBuilder.voiceLongName("algo-raro"))
        // Un nombre ya largo no se toca.
        val long = "Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)"
        assertEquals(long, SsmlBuilder.voiceLongName(long))
    }

    // ── SSML completo ───────────────────────────────────────────────────────

    @Test
    fun ssmlMatchesTheReferenceFormat() {
        val ssml = SsmlBuilder.build(
            voice = "es-MX-DaliaNeural",
            locale = "es-MX",
            rate = "+0%",
            pitch = "+0Hz",
            text = "Hola"
        )
        assertTrue(
            ssml.startsWith(
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
            )
        )
        // La referencia envía el nombre LARGO (verificado con mkssml en vivo).
        assertTrue(
            ssml.contains(
                "<voice name='Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)'>"
            )
        )
        assertTrue(
            ssml.contains("<prosody pitch='+0Hz' rate='+0%' volume='+0%'>Hola</prosody>")
        )
        assertTrue(ssml.endsWith("</voice></speak>"))
    }

    @Test
    fun ssmlEscapesTheText() {
        val ssml = SsmlBuilder.build(
            voice = "es-MX-DaliaNeural",
            locale = "es-MX",
            rate = "+0%",
            pitch = "+0Hz",
            text = "Pan & vino <3 \"siempre\""
        )
        assertTrue(ssml.contains("Pan &amp; vino &lt;3 &quot;siempre&quot;"))
        assertFalse(ssml.contains("<3"))
    }

    @Test
    fun minimalModeOmitsProsody() {
        val ssml = SsmlBuilder.build(
            voice = "es-MX-DaliaNeural",
            locale = "es-MX",
            rate = "+0%",
            pitch = "+0Hz",
            text = "Hola",
            minimal = true
        )
        assertFalse(ssml.contains("prosody"))
        assertTrue(
            ssml.contains(
                "<voice name='Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)'>Hola</voice>"
            )
        )
    }
}
