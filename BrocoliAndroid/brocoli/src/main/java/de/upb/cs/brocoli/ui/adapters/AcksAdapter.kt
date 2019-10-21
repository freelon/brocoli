package de.upb.cs.brocoli.ui.adapters

import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import de.upb.cs.brocoli.R
import de.upb.cs.brocoli.model.Ack
import de.upb.cs.brocoli.ui.inflate
import kotlinx.android.synthetic.main.recyclerview_item_row.view.*
import java.text.SimpleDateFormat
import java.util.*

class AcksAdapter(private var mData: MutableList<Ack>, private var mClickHandler: OnClickHandler) :
        RecyclerView.Adapter<AcksAdapter.AcksAdapterViewHolder>() {

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
     * Called when RecyclerView needs a new [AcksAdapterViewHolder] of the given type to represent
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
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AcksAdapterViewHolder {

        // Here you inflate the view from its layout and pass it in to a SimpleViewHolder.

        // this parent.inflate function is defined in Extensions.kt file
        val inflatedView = parent.inflate(R.layout.recyclerview_item_row, false)
        return AcksAdapterViewHolder(inflatedView)
    }

    /**
     * Called by RecyclerView to display the data at the specified position. This method should
     * update the contents of the [AcksAdapterViewHolder.itemView] to reflect the item at the given
     * position.
     *
     * @param holder The ViewHolder which should be updated to represent the contents of the
     * item at the given position in the data set.
     * @param position The position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: AcksAdapterViewHolder, position: Int) {
        holder.update(position)
    }

    /**
     * Used to set the data on a AcksAdapter if we've already created one.
     * This is handy when we get new data from the web
     * but don't want to create a new AcksAdapter to display it.
     */
    fun setData(data: List<Ack>) {
        mData = data.toMutableList()
        mDataCopy = data.toMutableList()
        notifyDataSetChanged()
        // Notifies the attached observers that the underlying data has been changed
        // and any View reflecting the data set should refresh itself.
    }

    /**
     * The interface that receives onClick messages
     *
     * - An on-click handler that we've defined to make it easy for an Activity to interface with Recycler View
     */
    interface OnClickHandler {
        fun onClick(Data: Ack)
    }

    fun filter(text: String) {
        var text = text
        mData.clear()
        if (text.isEmpty()) {
            mData.addAll(mDataCopy)
        } else {
            text = text.toLowerCase()
            for (ack in mDataCopy) {
                if (ack.id.toLowerCase().contains(text) || ack.expiryDate.toString().contains(text)) {
                    mData.add(ack)
                }
            }
        }
        notifyDataSetChanged()
    }


    inner class AcksAdapterViewHolder(v: View) : RecyclerView.ViewHolder(v), View.OnClickListener {

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
            val data: Ack = mData[this.adapterPosition]
            mClickHandler.onClick(data)
        }

        fun update(position: Int) {
            val ack: Ack = mData[position]

            mView.tv_data.text = ack.id
            mView.tv_date.text = formatDate(Date(ack.expiryDate))
        }
    }

    private fun formatDate(timestamp: Date): String {
//            val pattern = "dd:MM:yyyy"
        val pattern = "yyyy-MM-dd - HH:mm:ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        return simpleDateFormat.format(timestamp)
    }
}
