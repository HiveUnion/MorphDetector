package com.example.testauto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InfiniteScrollAdapter(private val dataList: List<ListItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_BOOT_ID = 0
        private const val VIEW_TYPE_DEVICE_ID = 1
        private const val VIEW_TYPE_ACCESSIBILITY = 2
        private const val VIEW_TYPE_APP_LIST = 3
        private const val VIEW_TYPE_VPN_STATUS = 4
        private const val VIEW_TYPE_LOCK_SCREEN = 5
    }

    class BootIdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    class DeviceIdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    class AccessibilityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val serviceName: TextView = itemView.findViewById(R.id.serviceName)
        val packageName: TextView = itemView.findViewById(R.id.packageName)
        val className: TextView = itemView.findViewById(R.id.className)
    }

    class AppListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    class VpnStatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    class LockScreenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun getItemViewType(position: Int): Int {
        return when (dataList[position].type) {
            ItemType.BOOT_ID -> VIEW_TYPE_BOOT_ID
            ItemType.DEVICE_ID -> VIEW_TYPE_DEVICE_ID
            ItemType.ACCESSIBILITY_SERVICE -> VIEW_TYPE_ACCESSIBILITY
            ItemType.VPN_STATUS -> VIEW_TYPE_VPN_STATUS
            ItemType.LOCK_SCREEN -> VIEW_TYPE_LOCK_SCREEN
            ItemType.APP_LIST -> VIEW_TYPE_APP_LIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_BOOT_ID -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                BootIdViewHolder(view)
            }
            VIEW_TYPE_DEVICE_ID -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                DeviceIdViewHolder(view)
            }
            VIEW_TYPE_ACCESSIBILITY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_accessibility_service, parent, false)
                AccessibilityViewHolder(view)
            }
            VIEW_TYPE_VPN_STATUS -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                VpnStatusViewHolder(view)
            }
            VIEW_TYPE_APP_LIST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                AppListViewHolder(view)
            }
            VIEW_TYPE_LOCK_SCREEN -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                LockScreenViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = dataList[position]

        when (holder) {
            is BootIdViewHolder -> {
                holder.textView.text = item.text
                // 设置 Boot ID 的样式
                holder.textView.textSize = 14f
                holder.textView.setPadding(32, 16, 32, 16)
                holder.textView.setTextColor(0xFF1976D2.toInt()) // 蓝色
            }
            is DeviceIdViewHolder -> {
                holder.textView.text = item.text
                // 设置设备标识符的样式
                if (item.text.startsWith("===")) {
                    // 标题项
                    holder.textView.textSize = 16f
                    holder.textView.setPadding(32, 16, 32, 8)
                    holder.textView.setTextColor(0xFF9C27B0.toInt()) // 紫色
                } else {
                    // 内容项
                    holder.textView.textSize = 14f
                    holder.textView.setPadding(48, 8, 32, 8)
                    holder.textView.setTextColor(0xFF000000.toInt()) // 黑色
                }
            }
            is AccessibilityViewHolder -> {
                if (item.text.startsWith("===")) {
                    // 标题项
                    holder.serviceName.text = item.text
                    holder.packageName.visibility = View.GONE
                    holder.className.visibility = View.GONE
                } else if (item.text.startsWith("没有启用的")) {
                    // 无服务项
                    holder.serviceName.text = item.text
                    holder.packageName.visibility = View.GONE
                    holder.className.visibility = View.GONE
                } else {
                    // 服务信息项
                    val lines = item.text.split("\n")
                    if (lines.size >= 4) {
                        val serviceName = lines[0].replace("服务: ", "").replace("自动化服务: ", "")
                        holder.serviceName.text = serviceName
                        holder.packageName.text = lines[1]
                        holder.className.text = lines[2]

                        // 设置状态颜色
                        val statusLine = lines[3]
                        if (statusLine.contains("运行中")) {
                            holder.serviceName.setTextColor(0xFF4CAF50.toInt()) // 绿色
                        } else {
                            holder.serviceName.setTextColor(0xFFFF5722.toInt()) // 红色
                        }

                        // 如果有详细信息，显示在类名下面
                        if (lines.size > 4) {
                            val details = lines.drop(4).joinToString("\n")
                            holder.className.text = "${holder.className.text}\n$details"
                        }
                    } else if (lines.size >= 3) {
                        val serviceName = lines[0].replace("服务: ", "").replace("自动化服务: ", "")
                        holder.serviceName.text = serviceName
                        holder.packageName.text = lines[1]
                        holder.className.text = lines[2]
                    } else {
                        holder.serviceName.text = item.text
                        holder.packageName.visibility = View.GONE
                        holder.className.visibility = View.GONE
                    }
                }
            }
            is VpnStatusViewHolder -> {
                holder.textView.text = item.text
                // 根据VPN状态设置文字颜色
                when {
                    item.text.contains("已连接") -> {
                        holder.textView.setTextColor(0xFF4CAF50.toInt()) // 绿色
                    }
                    item.text.contains("未连接") -> {
                        holder.textView.setTextColor(0xFFFF5722.toInt()) // 红色
                    }
                    else -> {
                        holder.textView.setTextColor(0xFF000000.toInt()) // 黑色
                    }
                }
            }
            is AppListViewHolder -> {
                holder.textView.text = item.text
            }
            is LockScreenViewHolder -> {
                holder.textView.text = item.text
                // 设置锁屏状态的样式
                when {
                    item.text.startsWith("===") -> {
                        // 标题项
                        holder.textView.textSize = 16f
                        holder.textView.setPadding(32, 16, 32, 8)
                        holder.textView.setTextColor(0xFF00ACC1.toInt()) // 青色
                    }
                    item.text.contains("✓") -> {
                        // 已设置项
                        holder.textView.textSize = 14f
                        holder.textView.setPadding(48, 8, 32, 8)
                        holder.textView.setTextColor(0xFF4CAF50.toInt()) // 绿色
                    }
                    item.text.contains("✗") -> {
                        // 未设置项
                        holder.textView.textSize = 14f
                        holder.textView.setPadding(48, 8, 32, 8)
                        holder.textView.setTextColor(0xFF757575.toInt()) // 灰色
                    }
                    else -> {
                        // 其他项
                        holder.textView.textSize = 14f
                        holder.textView.setPadding(48, 8, 32, 8)
                        holder.textView.setTextColor(0xFF000000.toInt()) // 黑色
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return dataList.size
    }
}
