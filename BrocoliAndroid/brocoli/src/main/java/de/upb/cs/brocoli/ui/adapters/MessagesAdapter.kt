package de.upb.cs.brocoli.ui.adapters

import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.model.AlgorithmContentMessage
import de.upb.cs.brocoli.model.Message
import de.upb.cs.brocoli.ui.inflate
import kotlinx.android.synthetic.main.recyclerview_item_row.view.*
import java.text.SimpleDateFormat
import java.util.*

class MessagesAdapter(private var mData: MutableList<Message>, private var mClickHandler: OnClickHandler) :
        RecyclerView.Adapter<MessagesAdapter.MessagesAdapterViewHolder>() {

    var mDataCopy = mData.toMutableList()

    /**
     * Returns the total number of items in the data set held by the adapter.
     *
     * @return The total number of items in this adapter.
     */
    override fun getItemCount(): Int {
        return mData.size
    }

    /**
     * Called when RecyclerView needs a new [MessagesAdapterViewHolder] of the given type to represent
     * an item.
     *
     *
     * This new ViewHolder should be constructed with a new View that can represent the items
     * of the given type. You can either create a new View manually or inflate it from an XML
     * layout file.
     *
     *
     * The new ViewHolder will be used to display items of the adapter using
     * [.onBindViewHolder]. Since it will be re-used to display
     * different items in the data set, it is a good idea to cache references to sub views of
     * the View to avoid unnecessary [View.findViewById] calls.
     *
     * @param parent The ViewGroup into which the new View will be added after it is bound to
     * an adapter position.
     * @param viewType The view type of the new View.
     *
     * @return A new ViewHolder that holds a View of the given view type.
     * @see .getItemViewType
     * @see .onBindViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessagesAdapterViewHolder {

        // Here you inflate the view from its layout and pass it in to a SimpleViewHolder.

        // this parent.inflate function is defined in Extensions.kt file
        val inflatedView = parent.inflate(R.layout.recyclerview_item_row, false)
        return MessagesAdapterViewHolder(inflatedView)
    }

    /**
     * Called by RecyclerView to display the data at the specified position. This method should
     * update the contents of the [MessagesAdapterViewHolder.itemView] to reflect the item at the given
     * position.
     *
     * @param holder The ViewHolder which should be updated to represent the contents of the
     * item at the given position in the data set.
     * @param position The position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: MessagesAdapterViewHolder, position: Int) {
        holder.update(position)
    }

    /**
     * Used to set the data on a DataAdapter if we've already created one.
     * This is handy when we get new data from the web
     * but don't want to create a new DataAdapter to display it.
     */
    fun setData(data: List<Message>) {
        mData = data.toMutableList()
        mDataCopy = mData.toMutableList()
        notifyDataSetChanged()
        // Notifies the attached observers that the underlying data has been changed
        // and any View reflecting the data set should refresh itself.
    }

    fun filter(text: String) {
        var text = text
        mData.clear()
        if (text.isEmpty()) {
            mData.addAll(mDataCopy)
        } else {
            text = text.toLowerCase()
            for (message in mDataCopy) {
                message as AlgorithmContentMessage
                if (message.id.toLowerCase().contains(text) || message.toId.toLowerCase().contains(text) || message.fromId.toLowerCase().contains(text) || message.serviceId.toString().toLowerCase().contains(text)) {
                    mData.add(message)
                }
            }
        }
        notifyDataSetChanged()
    }

    /**
     * The interface that receives onClick messages
     *
     * - An on-click handler that we've defined to make it easy for an Activity to interface with Recycler View
     */
    interface OnClickHandler {
        fun onClick(Data: Message)
    }

    inner class MessagesAdapterViewHolder(v: View) : RecyclerView.ViewHolder(v), View.OnClickListener {

        private val mView: View = v

        init {
            mView.setOnClickListener(this)
        }

        /**
         * This method is called by the child views during a click.
         *
         * @param v the View that was clicked on
         */
        override fun onClick(p0: View?) {
            // adapterPosition returns the Adapter position of the item represented by this ViewHolder.
            val data: Message = mData[this.adapterPosition]
            mClickHandler.onClick(data)
        }

        fun update(position: Int) {
            var message: Message = mData[position]

            when (message) {
                is AlgorithmContentMessage -> formatAlgorithmMessage(message)
                else -> mView.tv_data.text = message.toString()
            }

//            mView.tv_date.text = formatDate(message.timestamp)
        }

        private fun formatAlgorithmMessage(message: AlgorithmContentMessage) {
            mView.tv_ids.text = "From ${formatId(message.fromId)} - To ${formatId(message.toId)}"

            mView.tv_data.text = "${formatId(message.id)} [ServiceID: ${message.serviceId}, P: ${message.priority}]"

            mView.tv_date.text = "${formatDate(Date(message.timestamp))} [ttl: ${formatTtl(message.ttlHours)}]"
        }

        private fun formatDate(timestamp: Date): String {
//            val pattern = "dd:MM:yyyy"
            val pattern = "yyyy-MM-dd - HH:mm:ss"
            val simpleDateFormat = SimpleDateFormat(pattern)
            return simpleDateFormat.format(timestamp)
        }

        fun formatId(id: String): String {
            var rangemax = id.length - 1
            if (rangemax > 7) {
                rangemax = 7
            }
            return id.slice(0..rangemax)
        }

        fun formatTtl(byte: Byte): String {
            return byte.toString()+" hrs"
        }
    }
}
