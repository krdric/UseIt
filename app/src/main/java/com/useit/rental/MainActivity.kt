package com.useit.rental

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

private data class RentalItem(
    val id: String, val name: String, val category: String, val emoji: String,
    val price: Int, val area: String, val owner: String, val ownerUid: String,
    val description: String
)

class MainActivity : AppCompatActivity() {
    private val green = Color.rgb(31, 91, 70)
    private val ink = Color.rgb(31, 42, 37)
    private val muted = Color.rgb(112, 122, 116)
    private val canvas = Color.rgb(248, 248, 243)
    private val items = mutableListOf<RentalItem>()
    private val savedIds = mutableSetOf<String>()
    private var bookings = mutableListOf<Map<String, Any>>()
    private var ownerRequests = mutableListOf<Map<String, Any>>()
    private var selectedCategory = "All"
    private var currentTab = "Explore"
    private var query = ""
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        if (auth.currentUser == null) showAuthScreen() else { loadAccountData(); showScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (::auth.isInitialized) {
            auth.currentUser?.reload()?.addOnSuccessListener {
                if (::content.isInitialized && currentTab == "Profile") showProfile()
            }
        }
    }

    private fun showAuthScreen() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(canvas) }
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(32))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        page.addView(text("useit", 32, green, true))
        page.addView(text("Own less. Do more.", 25, ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(8))
        })
        page.addView(text("Sign in to find and rent useful things nearby.", 15, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        })
        page.addView(primaryButton("Continue with Google") { startGoogleSignIn() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        page.addView(primaryButton("Continue with phone") { showPhoneSignInDialog() },
            LinearLayout.LayoutParams(-1, dp(52)))
        page.addView(text("Phone sign-in uses an SMS verification code. Standard message rates may apply.", 12, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), 0)
        })
    }

    private fun startGoogleSignIn() {
        val clientIdResource = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (clientIdResource == 0) {
            toast("Enable Google sign-in in Firebase and download the updated google-services.json.")
            return
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(getString(clientIdResource))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(this).getCredentialAsync(
            this, request, null, ContextCompat.getMainExecutor(this),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential
                    if (credential !is CustomCredential ||
                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        toast("Choose a Google account to continue.")
                        return
                    }
                    try {
                        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                        auth.signInWithCredential(firebaseCredential).addOnCompleteListener(this@MainActivity) { task ->
                            if (task.isSuccessful) finishSignIn()
                            else toast(task.exception?.localizedMessage ?: "Google sign-in failed.")
                        }
                    } catch (error: GoogleIdTokenParsingException) {
                        toast("Could not read the selected Google account.")
                    }
                }

                override fun onError(error: GetCredentialException) {
                    if (error !is GetCredentialCancellationException) {
                        toast(error.localizedMessage ?: "Google sign-in failed.")
                    }
                }
            }
        )
    }

    private fun showPhoneSignInDialog() {
        val phoneField = field("e.g. +91 98765 43210").apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in with phone")
            .setMessage("Enter your number with its country code.")
            .setView(phoneField)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send code") { _, _ ->
                val phone = phoneField.text.toString().replace(" ", "").trim()
                if (!phone.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
                    phoneField.error = "Include country code, for example +91"
                    showPhoneSignInDialog()
                } else {
                    requestPhoneCode(phone)
                }
            }
            .show()
    }

    private fun requestPhoneCode(phoneNumber: String) {
        auth.setLanguageCode("en")
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: com.google.firebase.auth.PhoneAuthCredential) {
                signInWithPhoneCredential(credential)
            }

            override fun onVerificationFailed(error: com.google.firebase.FirebaseException) {
                toast(error.localizedMessage ?: "Could not send the SMS code. Check the number and try again.")
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                showOtpDialog(verificationId)
            }

            override fun onCodeAutoRetrievalTimeOut(verificationId: String) = Unit
        }
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun showOtpDialog(verificationId: String) {
        val codeField = field("6-digit code").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Verify your phone")
            .setMessage("Enter the code sent to your phone.")
            .setView(codeField)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Verify") { _, _ ->
                val code = codeField.text.toString().trim()
                if (code.length != 6) {
                    codeField.error = "Enter the 6-digit code"
                    showOtpDialog(verificationId)
                } else {
                    val credential = PhoneAuthProvider.getCredential(verificationId, code)
                    signInWithPhoneCredential(credential)
                }
            }
            .show()
    }

    private fun signInWithPhoneCredential(credential: com.google.firebase.auth.PhoneAuthCredential) {
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) finishSignIn()
            else toast(task.exception?.localizedMessage ?: "Phone verification failed.")
        }
    }

    private fun finishSignIn() {
        val user = auth.currentUser ?: return showAuthScreen()
        val profileRef = db.collection("users").document(user.uid)
        profileRef.get().addOnSuccessListener { snapshot ->
            val displayName = user.displayName?.takeIf { it.isNotBlank() }
                ?: user.phoneNumber ?: "UseIt member"
            val profile = mutableMapOf<String, Any>(
                "displayName" to displayName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (snapshot.exists()) {
                profileRef.update(profile).addOnSuccessListener { enterUserPanel() }
                    .addOnFailureListener { toast(it.localizedMessage ?: "Could not update your profile.") }
            } else {
                profile["createdAt"] = FieldValue.serverTimestamp()
                user.email?.let { profile["email"] = it }
                user.phoneNumber?.let { profile["phoneNumber"] = it }
                profileRef.set(profile).addOnSuccessListener { enterUserPanel() }
                    .addOnFailureListener { toast(it.localizedMessage ?: "Could not create your profile.") }
            }
        }.addOnFailureListener { toast(it.localizedMessage ?: "Could not load your profile.") }
    }

    private fun enterUserPanel() {
        currentTab = "Explore"
        loadAccountData()
        showScreen()
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 15f; setSingleLine(true); setPadding(dp(14), 0, dp(14), 0); background = rounded(Color.WHITE, 12)
    }

    private fun loadAccountData() {
        val user = auth.currentUser ?: return
        db.collection("listings").whereEqualTo("status", "active").get().addOnSuccessListener { snapshot ->
            items.clear()
            snapshot.documents.forEach { d ->
                items.add(RentalItem(d.id, d.getString("title") ?: "", d.getString("category") ?: "Other",
                    d.getString("emoji") ?: "📦", (d.getLong("dailyPrice") ?: 0L).toInt(),
                    d.getString("area") ?: "Nearby", d.getString("ownerName") ?: "Owner",
                    d.getString("ownerUid") ?: "", d.getString("description") ?: ""))
            }
            if (::content.isInitialized && currentTab == "Explore") showScreen()
        }.addOnFailureListener { toast("Could not load listings: ${it.localizedMessage}") }
        db.collection("users").document(user.uid).collection("saved").get().addOnSuccessListener { docs ->
            savedIds.clear(); docs.documents.forEach { savedIds.add(it.id) }
            if (::content.isInitialized && currentTab == "Saved") showScreen()
        }
        db.collection("bookings").whereEqualTo("renterUid", user.uid).get().addOnSuccessListener { docs ->
            bookings = docs.documents.map { it.data.orEmpty() + ("_id" to it.id) }.toMutableList()
            if (::content.isInitialized && currentTab == "Rentals") showScreen()
        }
        db.collection("bookings").whereEqualTo("ownerUid", user.uid).get().addOnSuccessListener { docs ->
            ownerRequests = docs.documents.map { it.data.orEmpty() + ("_id" to it.id) }.toMutableList()
            if (::content.isInitialized && currentTab == "Rentals") showScreen()
        }
    }

    private fun showScreen() {
        if (!::auth.isInitialized || auth.currentUser == null) { showAuthScreen(); return }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(canvas) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(bottomBar(), LinearLayout.LayoutParams(-1, dp(66))); setContentView(root)
        when (currentTab) { "Explore" -> showExplore(); "Saved" -> showSaved(); "Rentals" -> showRentals(); else -> showProfile() }
    }

    private fun showExplore() {
        content.removeAllViews()
        val page = pageContainer()
        content.addView(ScrollView(this).apply { addView(page) })
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("useit", 27, green, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(pill("📍  Bengaluru", Color.WHITE, ink, 13).apply { setPadding(dp(12), dp(9), dp(12), dp(9)) }); page.addView(top)
        page.addView(text("Own less. Do more.", 28, ink, true).apply { setPadding(0, dp(23), 0, dp(3)) })
        page.addView(text("Useful things, nearby and affordable.", 15, muted, false))
        val search = field("Search drill, tent, projector...").apply { setText(query) }
        page.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) })
        search.addTextChangedListenerSimple { query = it; renderItems(page) }
        page.addView(sectionTitle("Browse categories", ""))
        val categories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("All", "Tools", "Home", "Outdoor", "Electronics").forEach { category ->
            val active = selectedCategory == category
            categories.addView(pill(category, if (active) green else Color.WHITE, if (active) Color.WHITE else ink, 13).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { selectedCategory = category; showExplore() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categories) })
        page.addView(sectionTitle("Popular near you", "Low prices")); renderItems(page)
    }

    private fun EditText.addTextChangedListenerSimple(onChange: (String) -> Unit) {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChange(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun renderItems(page: LinearLayout) {
        page.findViewWithTag<View>("rental_list")?.let { page.removeView(it) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "rental_list" }
        val filtered = items.filter { (selectedCategory == "All" || it.category.equals(selectedCategory, true)) &&
            (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) || it.area.contains(query, true)) }.sortedBy { it.price }
        if (filtered.isEmpty()) list.addView(text(if (items.isEmpty()) "No listings yet. You can add the first one." else "No items found. Try another search.", 15, muted, false).apply { setPadding(dp(4), dp(22), dp(4), dp(22)) })
        filtered.forEach { list.addView(itemCard(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
        page.addView(list)
    }

    private fun itemCard(item: RentalItem): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(Color.WHITE, 18); setOnClickListener { showItemDetails(item) } }
        card.addView(TextView(this).apply { text = item.emoji; textSize = 38f; gravity = Gravity.CENTER; background = rounded(Color.rgb(239, 241, 231), 15) }, LinearLayout.LayoutParams(dp(82), dp(92)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(item.name, 15, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(if (item.id in savedIds) "♥" else "♡", 23, if (item.id in savedIds) green else muted, false).apply { setOnClickListener { toggleSaved(item) } })
        info.addView(row); info.addView(text("📍 ${item.area} · ${item.owner}", 12, muted, false).apply { setPadding(0, dp(5), 0, dp(8)) })
        info.addView(text("₹${item.price} / day", 16, green, true)); card.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); return card
    }

    private fun toggleSaved(item: RentalItem) {
        val user = auth.currentUser ?: return
        val ref = db.collection("users").document(user.uid).collection("saved").document(item.id)
        if (item.id in savedIds) ref.delete().addOnSuccessListener { savedIds.remove(item.id); showScreen() }.addOnFailureListener { toast(it.localizedMessage ?: "Could not remove saved item.") }
        else ref.set(mapOf("listingId" to item.id, "createdAt" to FieldValue.serverTimestamp())).addOnSuccessListener { savedIds.add(item.id); showScreen() }.addOnFailureListener { toast(it.localizedMessage ?: "Could not save item.") }
    }

    private fun showItemDetails(item: RentalItem) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(12)) }
        panel.addView(text("${item.emoji}  ${item.name}", 22, ink, true))
        panel.addView(text("📍 ${item.area} · by ${item.owner}", 14, muted, false).apply { setPadding(0, dp(10), 0, dp(16)) })
        panel.addView(text(item.description, 15, ink, false)); panel.addView(text("₹${item.price} / day", 20, green, true).apply { setPadding(0, dp(18), 0, dp(10)) })
        AlertDialog.Builder(this).setView(panel).setNegativeButton("Close", null).setPositiveButton("Request rental") { _, _ ->
            val user = auth.currentUser
            if (user == null) { showAuthScreen(); return@setPositiveButton }
            if (user.uid == item.ownerUid) { toast("You cannot rent your own listing."); return@setPositiveButton }
            val booking = mapOf("listingId" to item.id, "renterUid" to user.uid, "ownerUid" to item.ownerUid,
                "itemTitle" to item.name, "dailyPrice" to item.price, "status" to "requested",
                "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp())
            db.collection("bookings").add(booking).addOnSuccessListener {
                loadAccountData(); currentTab = "Rentals"; showScreen(); toast("Rental request sent to the owner.")
            }.addOnFailureListener { toast("Could not send request: ${it.localizedMessage}") }
        }.show()
    }

    private fun showSaved() = showSimpleList("Saved items", items.filter { it.id in savedIds }, "No saved items yet.")

    private fun showRentals() {
        content.removeAllViews(); val page = pageContainer(); content.addView(ScrollView(this).apply { addView(page) })
        page.addView(text("My rentals", 27, ink, true)); page.addView(text("Requests and bookings", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(20)) })
        page.addView(sectionTitle("My rental requests", ""))
        if (bookings.isEmpty()) page.addView(emptyState("📦", "No rentals yet", "Send a rental request for an item to get started."))
        bookings.forEach { page.addView(bookingCard(it, isOwner = false)) }
        page.addView(sectionTitle("Requests for my items", ""))
        if (ownerRequests.isEmpty()) page.addView(emptyState("📨", "No requests for your items", "Requests for your listings will appear here."))
        ownerRequests.forEach { page.addView(bookingCard(it, isOwner = true)) }
    }

    private fun bookingCard(data: Map<String, Any>, isOwner: Boolean): View {
        val title = data["itemTitle"]?.toString() ?: "Rental"
        val status = data["status"]?.toString() ?: "requested"
        val card = infoCard(if (status == "accepted") "✅" else "⏳", title, "₹${data["dailyPrice"] ?: "—"} / day · $status")
        if (isOwner && status == "requested") card.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title).setItems(arrayOf("Accept request", "Decline request")) { _, which ->
                val next = if (which == 0) "accepted" else "declined"
                db.collection("bookings").document(data["_id"].toString()).update(mapOf("status" to next, "updatedAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener { loadAccountData(); toast("Request $next.") }.addOnFailureListener { toast(it.localizedMessage ?: "Update failed.") }
            }.show()
        }
        return card
    }

    private fun showSimpleList(title: String, result: List<RentalItem>, empty: String) {
        content.removeAllViews(); val page = pageContainer(); content.addView(ScrollView(this).apply { addView(page) })
        page.addView(text(title, 27, ink, true)); page.addView(text("Items you saved", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(20)) })
        if (result.isEmpty()) page.addView(emptyState("♡", empty, "Explore items and save your favourites."))
        result.forEach { page.addView(itemCard(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    }

    private fun showProfile() {
        content.removeAllViews(); val page = pageContainer(); content.addView(ScrollView(this).apply { addView(page) })
        page.addView(text("Your account", 27, ink, true)); page.addView(text("Manage rentals and listings", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(22)) })
        val user = auth.currentUser
        page.addView(infoCard("👤", user?.displayName ?: "UseIt member", user?.email ?: user?.phoneNumber ?: "Signed in"))
        page.addView(primaryButton("＋  List an item") { showAddListing() })
        page.addView(primaryButton("Sign out") {
            auth.signOut()
            savedIds.clear()
            items.clear()
            showAuthScreen()
        })
    }

    private fun showAddListing() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), 0) }
        val name = field("Item name (e.g. Power drill)")
        val category = field("Category (Tools, Home, Outdoor...)")
        val price = field("Price per day in ₹").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val area = field("Area / neighbourhood")
        val description = field("Item description").apply { minLines = 2; gravity = Gravity.TOP }
        listOf(name, category, price, area, description).forEach { form.addView(it, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) }) }
        AlertDialog.Builder(this).setTitle("List an item").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add listing") { _, _ ->
            val user = auth.currentUser
            if (user == null) { showAuthScreen(); return@setPositiveButton }
            val title = name.text.toString().trim(); val amount = price.text.toString().toIntOrNull()
            if (title.isBlank() || amount == null || amount !in 1..200000) { toast("Enter an item name and a valid price from ₹1 to ₹2,00,000."); return@setPositiveButton }
            val cat = category.text.toString().trim().ifEmpty { "Other" }.replaceFirstChar { it.uppercase() }
            val listing = mapOf("ownerUid" to user.uid, "ownerName" to (user.displayName ?: "Owner"),
                "title" to title, "category" to cat, "emoji" to emojiFor(cat), "dailyPrice" to amount,
                "area" to area.text.toString().trim().ifEmpty { "Nearby" },
                "description" to description.text.toString().trim().ifEmpty { "Contact the owner for more details." },
                "status" to "active", "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp())
            db.collection("listings").add(listing).addOnSuccessListener {
                loadAccountData(); currentTab = "Explore"; selectedCategory = "All"; showScreen(); toast("Listing published.")
            }.addOnFailureListener { toast("Could not save listing: ${it.localizedMessage}") }
        }.show()
    }

    private fun emojiFor(category: String) = when (category.lowercase()) { "tools" -> "🔩"; "home" -> "🪜"; "outdoor" -> "⛺"; "electronics" -> "📽️"; else -> "📦" }

    private fun bottomBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
        listOf("Explore" to "⌂", "Saved" to "♡", "Rentals" to "▣", "Profile" to "☺").forEach { (label, icon) ->
            val active = currentTab == label
            val button = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { currentTab = label; if (label != "Profile") loadAccountData(); showScreen() } }
            button.addView(text(icon, 22, if (active) green else muted, true).apply { gravity = Gravity.CENTER })
            button.addView(text(label, 11, if (active) green else muted, active).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
            bar.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun pageContainer() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(22), dp(20), dp(24)) }
    private fun sectionTitle(title: String, action: String?): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(23), 0, dp(12)); addView(text(title, 18, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!action.isNullOrBlank()) addView(text(action, 12, green, true))
    }
    private fun infoCard(emoji: String, title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(Color.WHITE, 16)
        addView(text(emoji, 25, ink, false), LinearLayout.LayoutParams(dp(45), -2))
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(text(title, 15, ink, true)); addView(text(subtitle, 13, muted, false).apply { setPadding(0, dp(4), 0, 0) }) })
    }
    private fun emptyState(icon: String, title: String, description: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(30), dp(24), dp(30)); background = rounded(Color.WHITE, 18)
        addView(text(icon, 38, green, true).apply { gravity = Gravity.CENTER })
        addView(text(title, 18, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) })
        addView(text(description, 14, muted, false).apply { gravity = Gravity.CENTER })
    }
    private fun primaryButton(label: String, onClick: () -> Unit): View = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(green, 12); setOnClickListener { onClick() }
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun pill(label: String, fill: Int, foreground: Int, size: Int): TextView = text(label, size, foreground, true).apply { background = rounded(fill, 24) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
