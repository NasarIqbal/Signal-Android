package org.thoughtcrime.securesms.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.InviteActivity
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.calls.log.CallLogFilter
import org.thoughtcrime.securesms.calls.log.CallLogFragment
import org.thoughtcrime.securesms.components.settings.app.AppSettingsActivity
import org.thoughtcrime.securesms.components.settings.app.notifications.manual.NotificationProfileSelectionFragment
import org.thoughtcrime.securesms.conversationlist.ConversationListFragment
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.conversationlist.model.UnreadPaymentsLiveData
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.profiles.NotificationProfile
import org.thoughtcrime.securesms.notifications.profiles.NotificationProfiles
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.stories.settings.StorySettingsActivity
import org.thoughtcrime.securesms.stories.tabs.ConversationListTabsState
import org.thoughtcrime.securesms.stories.tabs.ConversationListTabsViewModel
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.DynamicTheme
import org.thoughtcrime.securesms.util.Material3OnScrollHelper
import org.thoughtcrime.securesms.util.TopToastPopup
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

class MainActivityListHostFragment : Fragment(R.layout.main_activity_list_host_fragment), ConversationListFragment.Callback, Material3OnScrollHelperBinder, CallLogFragment.Callback {

  companion object {
    private val TAG = Log.tag(MainActivityListHostFragment::class.java)
  }

  private val conversationListTabsViewModel: ConversationListTabsViewModel by viewModels(ownerProducer = { requireActivity() })
  private val disposables: LifecycleDisposable = LifecycleDisposable()

  private var previousTopToastPopup: TopToastPopup? = null

  private val destinationChangedListener = DestinationChangedListener()

  private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == MainActivity.RESULT_CONFIG_CHANGED) {
      requireActivity().recreate()
    }
  }

  private val toolbarCallback = object : MainToolbarCallback {
    override fun onNewGroupClick() {
      startActivity(CreateGroupActivity.newIntent(requireActivity()))
    }

    override fun onClearPassphraseClick() {
      val intent = Intent(requireActivity(), KeyCachingService::class.java)
      intent.setAction(KeyCachingService.CLEAR_KEY_ACTION)
      requireActivity().startService(intent)
    }

    override fun onMarkReadClick() {
      toolbarViewModel.markAllMessagesRead()
    }

    override fun onInviteFriendsClick() {
      val intent = Intent(requireContext(), InviteActivity::class.java)
      startActivity(intent)
    }

    override fun onFilterUnreadChatsClick() {
      toolbarViewModel.setChatFilter(ConversationFilter.UNREAD)
    }

    override fun onClearUnreadChatsFilterClick() {
      toolbarViewModel.setChatFilter(ConversationFilter.OFF)
    }

    override fun onSettingsClick() {
      openSettings.launch(AppSettingsActivity.home(requireContext()))
    }

    override fun onNotificationProfileClick() {
      NotificationProfileSelectionFragment.show(parentFragmentManager)
    }

    override fun onProxyClick() {
      startActivity(AppSettingsActivity.proxy(requireContext()))
    }

    override fun onSearchClick() {
      conversationListTabsViewModel.onSearchOpened()
      toolbarViewModel.setToolbarMode(MainToolbarMode.SEARCH)
      toolbarViewModel.emitEvent(MainToolbarViewModel.Event.Search.Open)
    }

    override fun onClearCallHistoryClick() {
      toolbarViewModel.clearCallHistory()
    }

    override fun onFilterMissedCallsClick() {
      toolbarViewModel.setCallLogFilter(CallLogFilter.MISSED)
    }

    override fun onClearCallFilterClick() {
      toolbarViewModel.setCallLogFilter(CallLogFilter.ALL)
    }

    override fun onStoryPrivacyClick() {
      startActivity(StorySettingsActivity.getIntent(requireContext()))
    }

    override fun onCloseSearchClick() {
      conversationListTabsViewModel.onSearchClosed()
      toolbarViewModel.setToolbarMode(MainToolbarMode.FULL)
      toolbarViewModel.emitEvent(MainToolbarViewModel.Event.Search.Close)
    }

    override fun onCloseArchiveClick() {
      getChildNavController().popBackStack()
    }

    override fun onSearchQueryUpdated(query: String) {
      toolbarViewModel.setSearchQuery(query)
    }

    override fun onNotificationProfileTooltipDismissed() {
      SignalStore.notificationProfile.hasSeenTooltip = true
      toolbarViewModel.setShowNotificationProfilesTooltip(false)
    }
  }

  private val toolbarViewModel: MainToolbarViewModel by activityViewModels()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    disposables.bindTo(viewLifecycleOwner)

    val toolbarContainer = view.findViewById<ComposeView>(R.id.toolbar_container)
    toolbarContainer.setContent {
      val state by toolbarViewModel.state.collectAsStateWithLifecycle()

      SignalTheme(isDarkMode = DynamicTheme.isDarkTheme(LocalContext.current)) {
        MainToolbar(
          state = state,
          callback = toolbarCallback
        )
      }
    }

    UnreadPaymentsLiveData().observe(viewLifecycleOwner) { unread ->
      toolbarViewModel.setHasUnreadPayments(unread.isPresent)
    }

    disposables += conversationListTabsViewModel.state.subscribeBy { state ->
      val controller: NavController = getChildNavController()
      when (controller.currentDestination?.id) {
        R.id.conversationListFragment -> goToStateFromConversationList(state, controller)
        R.id.conversationListArchiveFragment -> Unit
        R.id.storiesLandingFragment -> goToStateFromStories(state, controller)
        R.id.callLogFragment -> goToStateFromCalling(state, controller)
      }
    }

    disposables += conversationListTabsViewModel.getNotificationProfiles().subscribeBy { profiles ->
      updateNotificationProfileStatus(profiles)
    }
  }

  private fun getChildNavController(): NavController {
    return requireView().findViewById<View>(R.id.fragment_container).findNavController()
  }

  private fun goToStateFromConversationList(state: ConversationListTabsState, navController: NavController) {
    if (state.tab == MainNavigationDestination.CHATS) {
      return
    } else {
      val cameraFab = requireView().findViewById<View?>(R.id.camera_fab)
      val newConvoFab = requireView().findViewById<View?>(R.id.fab)

      val extras = when {
        cameraFab != null && newConvoFab != null -> {
          ViewCompat.setTransitionName(cameraFab, "camera_fab")
          ViewCompat.setTransitionName(newConvoFab, "new_convo_fab")

          FragmentNavigatorExtras(
            cameraFab to "camera_fab",
            newConvoFab to "new_convo_fab"
          )
        }

        else -> null
      }

      val destination = if (state.tab == MainNavigationDestination.STORIES) {
        R.id.action_conversationListFragment_to_storiesLandingFragment
      } else {
        R.id.action_conversationListFragment_to_callLogFragment
      }

      navController.navigate(
        destination,
        null,
        null,
        extras
      )
    }
  }

  private fun goToStateFromCalling(state: ConversationListTabsState, navController: NavController) {
    when (state.tab) {
      MainNavigationDestination.CALLS -> return
      MainNavigationDestination.CHATS -> navController.popBackStack(R.id.conversationListFragment, false)
      MainNavigationDestination.STORIES -> navController.navigate(R.id.action_callLogFragment_to_storiesLandingFragment)
    }
  }

  private fun goToStateFromStories(state: ConversationListTabsState, navController: NavController) {
    when (state.tab) {
      MainNavigationDestination.STORIES -> return
      MainNavigationDestination.CHATS -> navController.popBackStack(R.id.conversationListFragment, false)
      MainNavigationDestination.CALLS -> navController.navigate(R.id.action_storiesLandingFragment_to_callLogFragment)
    }
  }

  override fun onResume() {
    super.onResume()
    toolbarViewModel.refresh()

    requireView()
      .findViewById<View>(R.id.fragment_container)
      .findNavController()
      .addOnDestinationChangedListener(destinationChangedListener)

    if (conversationListTabsViewModel.isMultiSelectOpen()) {
      presentToolbarForMultiselect()
    }
  }

  override fun onPause() {
    super.onPause()
    requireView()
      .findViewById<View>(R.id.fragment_container)
      .findNavController()
      .removeOnDestinationChangedListener(destinationChangedListener)
  }

  private fun presentToolbarForConversationListFragment() {
    toolbarViewModel.setToolbarMode(MainToolbarMode.FULL, destination = MainNavigationDestination.CHATS)
  }

  private fun presentToolbarForConversationListArchiveFragment() {
    toolbarViewModel.setToolbarMode(MainToolbarMode.BASIC, destination = MainNavigationDestination.CHATS)
  }

  private fun presentToolbarForStoriesLandingFragment() {
    toolbarViewModel.setToolbarMode(MainToolbarMode.FULL, destination = MainNavigationDestination.STORIES)
  }

  private fun presentToolbarForCallLogFragment() {
    toolbarViewModel.setToolbarMode(MainToolbarMode.FULL, destination = MainNavigationDestination.CALLS)
  }

  private fun presentToolbarForMultiselect() {
    toolbarViewModel.setToolbarMode(MainToolbarMode.NONE)
  }

  override fun onDestroyView() {
    previousTopToastPopup = null
    super.onDestroyView()
  }

  override fun onMultiSelectStarted() {
    presentToolbarForMultiselect()
    conversationListTabsViewModel.onMultiSelectStarted()
  }

  override fun onMultiSelectFinished() {
    val currentDestination: NavDestination? = requireView().findViewById<View>(R.id.fragment_container).findNavController().currentDestination
    if (currentDestination != null) {
      presentToolbarForDestination(currentDestination)
    }

    conversationListTabsViewModel.onMultiSelectFinished()
  }

  override fun updateProxyStatus(state: WebSocketConnectionState) {
    if (SignalStore.proxy.isProxyEnabled) {
      val proxyState: MainToolbarState.ProxyState = when (state) {
        WebSocketConnectionState.CONNECTING, WebSocketConnectionState.DISCONNECTING, WebSocketConnectionState.DISCONNECTED -> MainToolbarState.ProxyState.CONNECTING
        WebSocketConnectionState.CONNECTED -> MainToolbarState.ProxyState.CONNECTED
        WebSocketConnectionState.AUTHENTICATION_FAILED, WebSocketConnectionState.FAILED, WebSocketConnectionState.REMOTE_DEPRECATED -> MainToolbarState.ProxyState.FAILED
        else -> MainToolbarState.ProxyState.NONE
      }

      toolbarViewModel.setProxyState(proxyState = proxyState)
    } else {
      toolbarViewModel.setProxyState(proxyState = MainToolbarState.ProxyState.NONE)
    }
  }

  private fun updateNotificationProfileStatus(notificationProfiles: List<NotificationProfile>) {
    val activeProfile = NotificationProfiles.getActiveProfile(notificationProfiles)
    if (activeProfile != null) {
      if (activeProfile.id != SignalStore.notificationProfile.lastProfilePopup) {
        view?.postDelayed({
          try {
            var fragmentView = view as? ViewGroup ?: return@postDelayed

            SignalStore.notificationProfile.lastProfilePopup = activeProfile.id
            SignalStore.notificationProfile.lastProfilePopupTime = System.currentTimeMillis()

            if (previousTopToastPopup?.isShowing == true) {
              previousTopToastPopup?.dismiss()
            }

            val fragment = parentFragmentManager.findFragmentByTag(BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
            if (fragment != null && fragment.isAdded && fragment.view != null) {
              fragmentView = fragment.requireView() as ViewGroup
            }

            previousTopToastPopup = TopToastPopup.show(fragmentView, R.drawable.ic_moon_16, getString(R.string.ConversationListFragment__s_on, activeProfile.name))
          } catch (e: Exception) {
            Log.w(TAG, "Unable to show toast popup", e)
          }
        }, 500L)
      }
      toolbarViewModel.setNotificationProfileEnabled(true)
    } else {
      toolbarViewModel.setNotificationProfileEnabled(false)
    }

    if (!SignalStore.notificationProfile.hasSeenTooltip && Util.hasItems(notificationProfiles)) {
      toolbarViewModel.setShowNotificationProfilesTooltip(true)
    }
  }

  private fun presentToolbarForDestination(destination: NavDestination) {
    when (destination.id) {
      R.id.conversationListFragment -> {
        conversationListTabsViewModel.isShowingArchived(false)
        presentToolbarForConversationListFragment()
      }

      R.id.conversationListArchiveFragment -> {
        conversationListTabsViewModel.isShowingArchived(true)
        presentToolbarForConversationListArchiveFragment()
      }

      R.id.storiesLandingFragment -> {
        conversationListTabsViewModel.isShowingArchived(false)
        presentToolbarForStoriesLandingFragment()
      }

      R.id.callLogFragment -> {
        conversationListTabsViewModel.isShowingArchived(false)
        presentToolbarForCallLogFragment()
      }
    }
  }

  private inner class DestinationChangedListener : NavController.OnDestinationChangedListener {
    override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
      presentToolbarForDestination(destination)
    }
  }

  override fun bindScrollHelper(recyclerView: RecyclerView) {
    Material3OnScrollHelper(
      activity = requireActivity(),
      views = listOf(),
      viewStubs = listOf(),
      onSetToolbarColor = {
        toolbarViewModel.setToolbarColor(it)
      },
      lifecycleOwner = viewLifecycleOwner
    ).attach(recyclerView)
  }

  override fun bindScrollHelper(recyclerView: RecyclerView, chatFolders: RecyclerView, setChatFolder: (Int) -> Unit) {
    Material3OnScrollHelper(
      activity = requireActivity(),
      views = listOf(chatFolders),
      viewStubs = listOf(),
      onSetToolbarColor = {
        toolbarViewModel.setToolbarColor(it)
      },
      lifecycleOwner = viewLifecycleOwner,
      setChatFolderColor = setChatFolder
    ).attach(recyclerView)
  }
}
