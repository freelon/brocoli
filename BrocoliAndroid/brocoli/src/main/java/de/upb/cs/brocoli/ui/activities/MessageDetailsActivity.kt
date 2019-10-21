package de.upb.cs.brocoli.ui.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import de.upb.cs.brocoli.R
import kotlinx.android.synthetic.main.activity_message_details.*
import java.text.SimpleDateFormat
import java.util.*

class MessageDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_details)

        var messageContent = intent.getStringExtra("content")
        var messageId = intent.getStringExtra("id")
        var toId = intent.getStringExtra("toId")
        var fromId = intent.getStringExtra("fromId")
        var time = intent.getLongExtra("time", 0)
        var formattedTime = formatDate(Date(time))
        var timeToLive = intent.getStringExtra("ttl")
        var serviceId = intent.getStringExtra("serviceId")
        var serviceName = intent.getStringExtra("serviceName")
        var priority = intent.getStringExtra("priority")

        var ttlEndTime = getTTLEndTime(Date(time), timeToLive)
        tv_content.text = "MESSAGE CONTENT:\n $messageContent"
        tv_id.text = "MESSAGE ID: $messageId"
        tv_from.text = "FROM: $fromId"
        tv_to.text = "TO: $toId"
        tv_time.text = "TIME: $formattedTime"
        tv_time_to_live.text = "TTL: $timeToLive hrs ($ttlEndTime)"
        tv_service.text = "SERVICE ID: $serviceId ($serviceName)"
        tv_priority.text = "PRIORITY: $priority"

    }

    private fun formatDate(timestamp: Date): String {
//            val pattern = "dd:MM:yyyy"
        val pattern = "dd:MM:yyyy - HH:mm:ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        return simpleDateFormat.format(timestamp)
    }

    private fun getTTLEndTime(timestamp: Date, timeToLive: String): String {
//            val pattern = "dd:MM:yyyy"

        val pattern = "dd:MM:yyyy - HH:mm:ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        val calendar = Calendar.getInstance()
        calendar.time = timestamp
        calendar.add(Calendar.HOUR_OF_DAY, Integer.parseInt(timeToLive))

        return simpleDateFormat.format(calendar.time)
    }

}
