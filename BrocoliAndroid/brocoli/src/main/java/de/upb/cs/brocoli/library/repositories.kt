package de.upb.cs.brocoli.library

import android.arch.lifecycle.LiveData

interface BrocoliMessageRepository {
    fun getAll(): LiveData<List<BrocoliMessage>>

    fun getAllAsList(): List<BrocoliMessage>

    fun getAllAsList(toId: String): List<BrocoliMessage>

    fun add(message: BrocoliMessage)

    fun deleteByIds(toIds: List<String>)
}
