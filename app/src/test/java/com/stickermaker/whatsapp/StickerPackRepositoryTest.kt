package com.stickermaker.whatsapp

import android.content.Context
import com.stickermaker.whatsapp.StickerPro.StickerPackRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.io.File

class StickerPackRepositoryTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var tempDir: File

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        tempDir = File("build/tmp/testStickers").apply { mkdirs() }
        `when`(mockContext.filesDir).thenReturn(tempDir)
    }

    @Test
    fun testCreateNewPack() {
        val packName = "Test Pack"
        val identifier = StickerPackRepository.createNewPack(mockContext, packName)
        
        assertNotNull(identifier)
        val pack = StickerPackRepository.loadPack(mockContext, identifier)
        assertNotNull(pack)
        assertEquals(packName, pack?.name)
        assertEquals(identifier, pack?.identifier)
    }

    @Test
    fun testSetPackName() {
        val identifier = StickerPackRepository.createNewPack(mockContext, "Old Name")
        val newName = "New Name"
        
        StickerPackRepository.setPackName(mockContext, identifier, newName)
        
        val pack = StickerPackRepository.loadPack(mockContext, identifier)
        assertEquals(newName, pack?.name)
    }
}
