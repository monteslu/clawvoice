package com.clawd.app.ui.agents

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clawd.app.R
import com.clawd.app.data.Agent
import com.clawd.app.data.SecureStorage
import com.clawd.app.databinding.DialogAgentListBinding
import com.clawd.app.databinding.ItemAgentBinding

class AgentListDialog(
    private val onAgentSelected: (Agent) -> Unit,
    private val onAddAgent: () -> Unit
) : DialogFragment() {

    private var _binding: DialogAgentListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAgentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val agents = SecureStorage.getAgents(requireContext())
        val activeId = SecureStorage.getActiveAgentId(requireContext())

        val adapter = AgentAdapter(
            agents = agents,
            activeId = activeId,
            onSelect = { agent ->
                onAgentSelected(agent)
                dismiss()
            },
            onDelete = { agent ->
                confirmDelete(agent)
            }
        )

        binding.agentList.layoutManager = LinearLayoutManager(requireContext())
        binding.agentList.adapter = adapter

        binding.addAgentButton.setOnClickListener {
            dismiss()
            onAddAgent()
        }
    }

    private fun confirmDelete(agent: Agent) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove ${agent.name}?")
            .setMessage("This will disconnect and remove this agent.")
            .setPositiveButton("Remove") { _, _ ->
                SecureStorage.removeAgent(requireContext(), agent.id)
                // Refresh the list
                val agents = SecureStorage.getAgents(requireContext())
                val activeId = SecureStorage.getActiveAgentId(requireContext())
                (binding.agentList.adapter as AgentAdapter).updateAgents(agents, activeId)

                // If no agents left, go to add
                if (agents.isEmpty()) {
                    dismiss()
                    onAddAgent()
                } else if (agent.id == activeId || activeId == null) {
                    // Active agent was deleted, switch to first
                    agents.firstOrNull()?.let {
                        onAgentSelected(it)
                        dismiss()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}

class AgentAdapter(
    private var agents: List<Agent>,
    private var activeId: String?,
    private val onSelect: (Agent) -> Unit,
    private val onDelete: (Agent) -> Unit
) : RecyclerView.Adapter<AgentAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAgentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAgentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val agent = agents[position]
        val isActive = agent.id == activeId

        holder.binding.agentName.text = agent.name
        holder.binding.agentUrl.text = agent.gatewayUrl
        holder.binding.activeIndicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE

        holder.itemView.setOnClickListener {
            if (!isActive) onSelect(agent)
        }

        holder.binding.deleteButton.setOnClickListener {
            onDelete(agent)
        }
    }

    override fun getItemCount() = agents.size

    fun updateAgents(newAgents: List<Agent>, newActiveId: String?) {
        agents = newAgents
        activeId = newActiveId
        notifyDataSetChanged()
    }
}
