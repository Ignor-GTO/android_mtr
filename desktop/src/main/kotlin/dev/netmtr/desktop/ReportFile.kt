package dev.netmtr.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReportFile {
    fun writePdf(report: String, destination: File) {
        val fontFile = windowsFont()
        val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(LocalDateTime.now())
        val lines = "Spectr IT NetMTR\nОтчёт $stamp\n\n$report"
            .replace("\r", "")
            .split('\n')
        PDDocument().use { document ->
            val font = PDType0Font.load(document, fontFile)
            var page = PDPage(PDRectangle.A4)
            document.addPage(page)
            var content = PDPageContentStream(document, page)
            val size = 9f
            val leading = 12f
            var y = page.mediaBox.height - 36f
            content.beginText()
            content.setFont(font, size)
            content.newLineAtOffset(36f, y)
            for (line in lines) {
                if (y < 36f) {
                    content.endText()
                    content.close()
                    page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    content = PDPageContentStream(document, page)
                    y = page.mediaBox.height - 36f
                    content.beginText()
                    content.setFont(font, size)
                    content.newLineAtOffset(36f, y)
                }
                content.showText(drawable(line, font))
                content.newLineAtOffset(0f, -leading)
                y -= leading
            }
            content.endText()
            content.close()
            destination.parentFile?.mkdirs()
            document.save(destination)
        }
    }

    fun copy(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    private fun windowsFont(): File {
        val windows = System.getenv("WINDIR") ?: "C:\\Windows"
        return listOf("consola.ttf", "arial.ttf", "segoeui.ttf")
            .map { File(windows, "Fonts/$it") }
            .firstOrNull { it.isFile }
            ?: throw IllegalStateException("Не найден шрифт Windows для PDF")
    }

    private fun drawable(line: String, font: PDType0Font): String {
        if (line.isEmpty()) return " "
        val out = StringBuilder()
        for (char in line.take(140)) {
            val ok = runCatching { font.encode(char.toString()) }.isSuccess
            out.append(if (ok) char else ' ')
        }
        return out.toString().ifEmpty { " " }
    }
}
