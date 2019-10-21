package de.upb.cs.brocoli.ui.adapters

import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import de.upb.cs.brocoli.ui.views.AcksFragment
import de.upb.cs.brocoli.ui.views.MessagesFragment

class DebugPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {
    override fun getItem(position: Int): Fragment {
        return when (position) {
            0 -> {
                MessagesFragment()
            }
            else -> {
                return AcksFragment()
            }
        }
    }

    override fun getCount(): Int {
        return 2
    }

    override fun getPageTitle(position: Int): CharSequence {
        return when (position) {
            0 -> MessagesFragment.PAGE_TITLE
            else -> {
                return AcksFragment.PAGE_TITLE
            }
        }
    }
}
