package de.upb.cs.brocoli.ui.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.format.DateFormat
import de.upb.cs.brocoli.R
import kotlinx.android.synthetic.main.activity_ack_details.*
import java.util.*

class AckDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ack_details)

        var ackId = intent.getStringExtra("id")
        var expiry = intent.getLongExtra("expiryDate", 0)
        val cal = Calendar.getInstance(Locale.ENGLISH)
        cal.setTimeInMillis(expiry)
        val date = DateFormat.format("dd-MM-yyyy hh:mm:ss", cal).toString()

        tv_id.text = "ID: $ackId"
        tv_expiry.text = "Until: $date"


    }
}
