package dev.diogo.conversorlocal

import dev.diogo.conversorlocal.conversion.Conversion
import dev.diogo.conversorlocal.files.Documents
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun androidOnlyExposesTheFourRequestedConversions() {
        assertEquals(4, Conversion.entries.size)
        assertEquals(listOf("pdf"), Conversion.targets(listOf("docx", "pptx")))
        assertEquals(listOf("docx", "pptx"), Conversion.targets(listOf("pdf", "pdf")))
        assertTrue(Conversion.targets(listOf("pdf", "docx")).isEmpty())
        assertTrue(Conversion.targets(listOf("odt")).isEmpty())
    }
    @Test fun suffixesPreserveAccentAndExistingNames() {
        assertEquals("Artigo com ação (2).docx", Documents.availableName("Artigo com ação.pdf", "docx", setOf("Artigo com ação.docx", "Artigo com ação (1).docx")))
        assertEquals("Artigo (1).pdf", Documents.availableName("Artigo.docx", "pdf", setOf("artigo.PDF")))
    }
}
