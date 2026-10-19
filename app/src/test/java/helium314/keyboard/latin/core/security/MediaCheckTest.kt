package helium314.keyboard.latin.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCheckTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `pictures are known by their first bytes`() {
        assertEquals(MediaCheck.Kind.IMAGE, MediaCheck.kind(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(MediaCheck.Kind.IMAGE, MediaCheck.kind(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(MediaCheck.Kind.IMAGE, MediaCheck.kind(ascii("GIF89a....")))
        assertEquals(MediaCheck.Kind.IMAGE, MediaCheck.kind(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(MediaCheck.Kind.IMAGE, MediaCheck.kind(ascii("\u0000\u0000\u0000\u0018ftypheic")))
    }

    @Test
    fun `films and sounds too`() {
        assertEquals(MediaCheck.Kind.VIDEO, MediaCheck.kind(ascii("\u0000\u0000\u0000\u0018ftypisom")))
        assertEquals(MediaCheck.Kind.VIDEO, MediaCheck.kind(ascii("\u0000\u0000\u0000\u0014ftyp3gp4")))
        assertEquals(MediaCheck.Kind.VIDEO, MediaCheck.kind(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals(MediaCheck.Kind.AUDIO, MediaCheck.kind(ascii("\u0000\u0000\u0000\u0018ftypM4A ")))
        assertEquals(MediaCheck.Kind.AUDIO, MediaCheck.kind(ascii("#!AMR\n")))
        assertEquals(MediaCheck.Kind.AUDIO, MediaCheck.kind(ascii("OggS\u0000")))
        assertEquals(MediaCheck.Kind.AUDIO, MediaCheck.kind(ascii("ID3\u0004")))
    }

    @Test
    fun `what is none of those is a file, whatever its sender called it`() {
        assertEquals(MediaCheck.Kind.OTHER, MediaCheck.kind(ascii("<svg xmlns=")))
        assertEquals(MediaCheck.Kind.OTHER, MediaCheck.kind(ascii("PK\u0003\u0004")))
        assertEquals(MediaCheck.Kind.OTHER, MediaCheck.kind(ascii("%PDF-1.7")))
        assertEquals(MediaCheck.Kind.OTHER, MediaCheck.kind(ByteArray(0)))
    }

    // The cases of https://mimesniff.spec.whatwg.org/#context-specific-sniffing:
    // bytes of the context's type pass, any other is refused the context.
    @Test
    fun `a claimed type goes out only when the bytes agree`() {
        val jpeg = MediaCheck.kind(bytes(0xFF, 0xD8, 0xFF, 0xE0))
        val text = MediaCheck.kind(ascii("BEGIN:VCARD"))
        val mp4 = MediaCheck.kind(ascii("\u0000\u0000\u0000\u0018ftypisom"))
        val mp3 = MediaCheck.kind(ascii("ID3\u0004"))
        assertEquals("image/jpeg", MediaCheck.typeFor(jpeg, "image/jpeg"))
        assertEquals("image/jpeg", MediaCheck.typeFor(jpeg, " IMAGE/JPEG; name=a.jpg"))
        assertEquals("application/octet-stream", MediaCheck.typeFor(text, "image/jpeg"))
        assertEquals("application/octet-stream", MediaCheck.typeFor(jpeg, "video/mp4"))
        assertEquals("application/octet-stream", MediaCheck.typeFor(mp3, "image/png"))
        assertEquals("application/octet-stream", MediaCheck.typeFor(MediaCheck.Kind.OTHER, "audio/amr"))
        assertEquals("audio/3gpp", MediaCheck.typeFor(mp4, "audio/3gpp"))
        assertEquals("video/mp4", MediaCheck.typeFor(mp4, "video/mp4"))
        assertEquals("text/x-vcard", MediaCheck.typeFor(text, "text/x-vcard"))
        assertEquals("", MediaCheck.typeFor(text, ""))
    }
}
