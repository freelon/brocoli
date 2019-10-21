package de.upb.cs.brocoli.library

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.singleton
import de.upb.cs.brocoli.database.InMemoryRepository
import de.upb.cs.brocoli.model.AlgorithmMessageRepository
import de.upb.cs.brocoli.model.ContactRepository

val repositoryModule = Kodein.Module {
    val combinedRepository = singleton { InMemoryRepository() }
    bind<AlgorithmMessageRepository>() with combinedRepository
    bind<ContactRepository>() with combinedRepository
}

val connectivityModule = Kodein.Module {

}
