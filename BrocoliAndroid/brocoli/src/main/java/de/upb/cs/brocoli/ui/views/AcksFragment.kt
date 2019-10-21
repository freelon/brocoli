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
import de.upb.cs.brocoli.model.Ack
import de.upb.cs.brocoli.model.AckRepository
import de.upb.cs.brocoli.ui.activities.AckDetailsActivity
import de.upb.cs.brocoli.ui.activities.DebugActivity
import de.upb.cs.brocoli.ui.adapters.AcksAdapter
import kotlinx.android.synthetic.main.fragment_acks.*

class AcksFragment : Fragment(), AcksAdapter.OnClickHandler, SearchView.OnQueryTextListener {

    private lateinit var linearLM: LinearLayoutManager
    private lateinit var adapter: AcksAdapter

    private lateinit var ackRepository: AckRepository
    var acks: List<Ack>? = null

    companion object {
        private val TAG = AcksFragment::class.java.simpleName
        const val PAGE_TITLE = "Acknowledgments"
    }

    override fun onClick(Data: Ack) {
        var ack = (Data as Ack)
        val intent = Intent(activity, AckDetailsActivity::class.java)
        intent.putExtra("id", ack.id)
        intent.putExtra("expiryDate", ack.expiryDate)

        startActivity(intent)
        Log.d(TAG, "Size of the ack repo is ${ackRepository.getAcknowledgementsAsList().size}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true);
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val rootView = inflater.inflate(R.layout.fragment_acks, container, false)


        (activity as DebugActivity).kodeinFlag.observe(activity as DebugActivity, Observer {
            if (it == true) {
                createAckRepository()
            } else {
                Log.d(TAG, "The activity is still not connected to the service")
            }
        })
        // Layout Manager manages rows in the Recycler View
        linearLM = LinearLayoutManager(this.activity, LinearLayoutManager.VERTICAL, false)
        rootView.findViewById<RecyclerView>(R.id.recyclerview_acks).layoutManager = linearLM

        // Adapter prepares data for rows which will be shown in Recycler View

        if (acks != null) {
            adapter = AcksAdapter(acks!!.toMutableList(), this)
        } else {
            adapter = AcksAdapter(mutableListOf(), this)
        }
        rootView.findViewById<RecyclerView>(R.id.recyclerview_acks).adapter = adapter

        return rootView
    }

    fun createAckRepository() {

        ackRepository = (activity as DebugActivity).kodein?.instance<AckRepository>()!!

        ackRepository.getAcknowledgements().observe(activity as DebugActivity, Observer {
            Log.d(TAG, "Number of Acknowledgment is ${it!!.size}")
            adapter.setData(it)
        })
        acks = ackRepository.getAcknowledgementsAsList()

        Log.d(TAG, "Number of acknowledgements is ${(acks as List<Ack>).size}")
    }

    override fun onStart() {
        super.onStart()
        /*
         * Use this setting to improve performance if you know that changes in content do not
         * change the child layout size in the RecyclerView
         */
        recyclerview_acks.setHasFixedSize(true)

        /* Once all of our views are setup, we can load the weather data. */
        loadData()
    }

    private fun loadData() {
        showDataView()
    }

    private fun showDataView() {
        tv_error_message_display.visibility = View.INVISIBLE
        recyclerview_acks.visibility = View.VISIBLE
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
