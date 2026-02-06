package com.clawd.app.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.clawd.app.R
import com.clawd.app.databinding.ItemMessageBinding
import com.clawd.app.network.protocol.ChatMessage
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

class MessageAdapter : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private var markwon: Markwon? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        if (markwon == null) {
            markwon = Markwon.builder(parent.context)
                .usePlugin(TablePlugin.create(parent.context))
                .build()
        }
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding, markwon!!)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding,
        private val markwon: Markwon
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val isUser = message.role == "user"

            // Render markdown for assistant messages, plain text for user
            if (isUser) {
                binding.messageText.text = message.content
            } else {
                markwon.setMarkdown(binding.messageText, message.content)
            }

            val layoutParams = binding.messageText.layoutParams as FrameLayout.LayoutParams

            if (isUser) {
                layoutParams.gravity = Gravity.END
                binding.messageText.setBackgroundResource(R.drawable.bubble_user)
            } else {
                layoutParams.gravity = Gravity.START
                binding.messageText.setBackgroundResource(R.drawable.bubble_assistant)
            }

            binding.messageText.layoutParams = layoutParams
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.ts == newItem.ts && oldItem.role == newItem.role
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
