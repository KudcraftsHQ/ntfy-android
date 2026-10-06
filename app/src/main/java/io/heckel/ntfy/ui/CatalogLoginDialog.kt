package io.heckel.ntfy.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.service.CatalogSync
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.normalizeBaseUrl
import io.heckel.ntfy.util.validBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * kudcrafts: catalog. Username + password -> access token (POST /v1/account/token). The password is
 * only used for that one request and never stored; the token is stored as the server's user.
 */
class CatalogLoginDialog : DialogFragment() {
    private lateinit var serverLayout: TextInputLayout
    private lateinit var serverView: TextInputEditText
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var usernameView: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var passwordView: TextInputEditText
    private lateinit var progress: ProgressBar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_catalog_login_dialog, null)
        serverLayout = view.findViewById(R.id.kc_login_server_layout)
        serverView = view.findViewById(R.id.kc_login_server)
        usernameLayout = view.findViewById(R.id.kc_login_username_layout)
        usernameView = view.findViewById(R.id.kc_login_username)
        passwordLayout = view.findViewById(R.id.kc_login_password_layout)
        passwordView = view.findViewById(R.id.kc_login_password)
        progress = view.findViewById(R.id.kc_login_progress)

        if (savedInstanceState == null) {
            val repository = Repository.getInstance(requireContext())
            serverView.setText(repository.getCatalogBaseUrl() ?: getString(R.string.app_base_url))
            usernameView.setText(arguments?.getString(ARG_USERNAME) ?: "")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.kc_login_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.kc_login_dialog_button_sign_in, null) // Overridden below to keep the dialog open
            .setNegativeButton(R.string.kc_login_dialog_button_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { signIn(dialog) }
        }
        return dialog
    }

    private fun signIn(dialog: AlertDialog) {
        serverLayout.error = null
        usernameLayout.error = null
        passwordLayout.error = null
        val server = serverView.text?.toString()?.trim().orEmpty()
        val username = usernameView.text?.toString()?.trim().orEmpty()
        val password = passwordView.text?.toString().orEmpty()
        if (!validBaseUrl(server)) {
            serverLayout.error = getString(R.string.kc_login_dialog_error_server)
            return
        }
        if (!server.lowercase().startsWith("https://")) {
            serverLayout.error = getString(R.string.kc_login_dialog_error_https) // Password and token never travel in clear text
            return
        }
        if (username.isEmpty()) {
            usernameLayout.error = getString(R.string.kc_login_dialog_error_username)
            return
        }
        if (password.isEmpty()) {
            passwordLayout.error = getString(R.string.kc_login_dialog_error_password)
            return
        }
        val baseUrl = normalizeBaseUrl(server)
        setBusy(dialog, true)
        val appContext = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val error = try {
                CatalogSync.signIn(appContext, baseUrl, username, password)
                null
            } catch (e: ApiService.UnauthorizedException) {
                appContext.getString(R.string.kc_login_dialog_error_credentials)
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed: ${e.message}", e)
                appContext.getString(R.string.kc_login_dialog_error_other, e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                setBusy(dialog, false)
                if (error == null) {
                    Toast.makeText(appContext, appContext.getString(R.string.kc_login_dialog_success, username), Toast.LENGTH_LONG).show()
                    parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
                    dismiss()
                } else {
                    passwordLayout.error = error
                }
            }
        }
    }

    private fun setBusy(dialog: AlertDialog, busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        serverView.isEnabled = !busy
        usernameView.isEnabled = !busy
        passwordView.isEnabled = !busy
    }

    companion object {
        const val TAG = "NtfyCatalogLoginDialog"
        const val RESULT_KEY = "kc_catalog_login_result"
        private const val ARG_USERNAME = "username"

        fun newInstance(username: String? = null): CatalogLoginDialog {
            return CatalogLoginDialog().apply {
                arguments = Bundle().apply { putString(ARG_USERNAME, username) }
            }
        }
    }
}
