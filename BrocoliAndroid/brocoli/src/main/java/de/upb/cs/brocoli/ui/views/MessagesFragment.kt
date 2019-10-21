package de.upb.cs.brocoli.ui.views

import android.arch.lifecycle.Observer
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.view.MenuItemCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.SearchView
import android.util.Log
import android.view.*
import com.github.salomonbrys.kodein.instance
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.AlgorithmMessageRepository
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.ui.activities.DebugActivity
import de.upb.cs.brocoli.ui.activities.MessageDetailsActivity
import de.upb.cs.brocoli.ui.adapters.MessagesAdapter
import kotlinx.android.synthetic.main.fragment_messages.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder

class MessagesFragment : Fragment(), MessagesAdapter.OnClickHandler, SearchView.OnQueryTextListener {

    private lateinit var linearLM: LinearLayoutManager
    private lateinit var adapter: MessagesAdapter

    private lateinit var messageRepository: AlgorithmMessageRepository
    var messages: List<Message>? = null

    companion object {
        private val TAG = MessagesFragment::class.java.simpleName
        const val PAGE_TITLE = "Messages"
    }

    override fun onClick(Data: Message) {
        var message = (Data as AlgorithmContentMessage)

        var decoder: CharsetDecoder = Charset.forName("UTF-8").newDecoder()
        var buf: ByteBuffer = ByteBuffer.wrap(message.content.toByteArray())

        var messageBody: String

        try {
            decoder.decode(buf)
            messageBody = String(message.content.toByteArray())
        } catch (e: CharacterCodingException) {
            Log.e(TAG, e.message)
            messageBody = "Binary Message [SIZE: ${message.content.size}]"
        }
        val intent = Intent(activity, MessageDetailsActivity::class.java)
        intent.putExtra("content", messageBody)
        intent.putExtra("fromId", message.fromId)
        intent.putExtra("toId", message.toId)
        intent.putExtra("id", message.id)
        intent.putExtra("serviceId", message.serviceId.toString())
        intent.putExtra("ttl", message.ttlHours.toString())
        intent.putExtra("time", message.timestamp)
        intent.putExtra("priority", message.priority.name)
        intent.putExtra("serviceName", (activity as DebugActivity).getServiceName(message.serviceId))
        startActivity(intent)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setHasOptionsMenu(true)

    }

    fun createMessageRepository() {
        messageRepository = (activity as DebugActivity).kodein?.instance<AlgorithmMessageRepository>()!!

        messageRepository.getAllMessages().observe(activity as DebugActivity, Observer {
            Log.d(TAG, "Number of messages changed: ${it!!.size}")
            adapter.setData(it)
        })

        messages = messageRepository.getAllMessagesAsList()

        Log.d(TAG, "Number of messages is ${(messages as List<AlgorithmContentMessage>).size}")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val rootView = inflater.inflate(R.layout.fragment_messages, container, false)

        (activity as DebugActivity).kodeinFlag.observe(activity as DebugActivity, Observer {
            if (it == true) {
                createMessageRepository()
            } else {
                Log.d(TAG, "The activity is still not connected to the service")
            }
        })

        // Layout Manager manages rows in the Recycler View
        linearLM = LinearLayoutManager(this.activity, LinearLayoutManager.VERTICAL, false)
        rootView.findViewById<RecyclerView>(R.id.recyclerview_messages).layoutManager = linearLM

        // Adapter prepares data for rows which will be shown in Recycler View
        if (messages != null) {
            adapter = MessagesAdapter(messages!!.toMutableList(), this)
        } else {
            adapter = MessagesAdapter(mutableListOf(), this)
        }

        rootView.findViewById<RecyclerView>(R.id.recyclerview_messages).adapter = adapter

        return rootView

    }

    override fun onStart() {
        super.onStart()
        /*
         * Use this setting to improve performance if you know that changes in content do not
         * change the child layout size in the RecyclerView
         */
        recyclerview_messages.setHasFixedSize(true)

        /* Once all of our views are setup, we can load the weather data. */
        loadData()
    }

    private fun loadData() {
        showDataView()
    }

    private fun showDataView() {
        tv_error_message_display.visibility = View.INVISIBLE
        recyclerview_messages.visibility = View.VISIBLE
    }

    override fun onQueryTextChange(query: String): Boolean {
        // Here is where we are going to implement the filter logic
        adapter.filter(query)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        adapter.filter(query)
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.filter_search_menu, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = MenuItemCompat.getActionView(searchItem) as SearchView
        searchView.setOnQueryTextListener(this)
    }
}
