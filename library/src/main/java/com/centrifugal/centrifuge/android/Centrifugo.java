package com.centrifugal.centrifuge.android;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.centrifugal.centrifuge.android.async.Future;
import com.centrifugal.centrifuge.android.client.WebsocketClient;
import com.centrifugal.centrifuge.android.config.ReconnectConfig;
import com.centrifugal.centrifuge.android.credentials.Token;
import com.centrifugal.centrifuge.android.credentials.User;
import com.centrifugal.centrifuge.android.listener.ConnectionListener;
import com.centrifugal.centrifuge.android.listener.DataMessageListener;
import com.centrifugal.centrifuge.android.listener.DownstreamMessageListener;
import com.centrifugal.centrifuge.android.listener.JoinLeaveListener;
import com.centrifugal.centrifuge.android.listener.SubscriptionListener;
import com.centrifugal.centrifuge.android.message.DataMessage;
import com.centrifugal.centrifuge.android.message.DownstreamMessage;
import com.centrifugal.centrifuge.android.message.SubscribeMessage;
import com.centrifugal.centrifuge.android.message.history.HistoryMessage;
import com.centrifugal.centrifuge.android.message.presence.JoinMessage;
import com.centrifugal.centrifuge.android.message.presence.LeftMessage;
import com.centrifugal.centrifuge.android.message.presence.PresenceMessage;
import com.centrifugal.centrifuge.android.subscription.ActiveSubscription;
import com.centrifugal.centrifuge.android.subscription.SubscriptionRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import timber.log.Timber;

/**
 * This file is part of centrifuge-android
 * Created by semyon on 29.04.16.
 */
public class Centrifugo {
	private static final String PRIVATE_CHANNEL_PREFIX = "$";

	private String mUserId;

	private String mClientId;

	private String mToken;

	private String mTokenTimestamp;

	private String mInfo;

	@Nullable
	private ReconnectConfig reconnectConfig;

	private WebsocketClient mClient;

	private Map<String, ActiveSubscription> subscribedChannels = new HashMap<>();

	private List<SubscriptionRequest> channelsToSubscribe = new ArrayList<>();

	@Nullable
	private DataMessageListener dataMessageListener;

	@Nullable
	private ConnectionListener connectionListener;

	@Nullable
	private SubscriptionListener subscriptionListener;

	@Nullable
	private JoinLeaveListener joinLeaveListener;

	private Map<String, DownstreamMessageListener> commandListeners = new HashMap<>();
	private boolean mDebug = false;

	protected Centrifugo(final String wsURI, final String userId, final String clientId, final String token, final String tokenTimestamp, final String info)
			throws IOException, NoSuchAlgorithmException {
		mUserId = userId;
		mClientId = clientId;
		mToken = token;
		mTokenTimestamp = tokenTimestamp;
		mInfo = info;

		mClient = new WebsocketClient(wsURI);
		mClient.performConnection();
		mClient.setOnConnectingListener(new WebsocketClient.OnConnectingListener() {
			@Override
			public void onStart() {
			}
			@Override
			public void onFinish() {
				onOpen();
			}
		});

		mClient.setOnErrorListener((websocket, cause) -> Centrifugo.this.onError(cause));
		mClient.setOnMessageListener((ws, message) -> {
			try {
				Object object = new JSONTokener(message).nextValue();
				if (object instanceof JSONObject) {
					JSONObject messageObj = (JSONObject) object;
					Centrifugo.this.onMessage(messageObj);
				} else if (object instanceof JSONArray) {
					JSONArray messageArray = new JSONArray(message);
					for (int i = 0; i < messageArray.length(); i++) {
						JSONObject messageObj = messageArray.optJSONObject(i);
						Centrifugo.this.onMessage(messageObj);
					}
				}
			} catch (JSONException e) {
				logErrorWhen("during message handling", e);
			}
		});
		mClient.setOnCloseListener(Centrifugo.this::onClose);
	}
	public void setDebug(boolean debug) {
		mDebug = debug;
		if (mDebug) {
			Timber.plant(new Timber.DebugTree());
		}
	}

	public void connect()
			throws IOException, NoSuchAlgorithmException {
		mClient.connect();
	}

	public void disconnect() {
		mClient.disconnect();
	}

	@Nullable
	public JoinLeaveListener getJoinLeaveListener() {
		return joinLeaveListener;
	}

	public void setJoinLeaveListener(@Nullable final JoinLeaveListener joinLeaveListener) {
		this.joinLeaveListener = joinLeaveListener;
	}

	public void setSubscriptionListener(@Nullable final SubscriptionListener subscriptionListener) {
		this.subscriptionListener = subscriptionListener;
	}

	public void setConnectionListener(@Nullable final ConnectionListener connectionListener) {
		this.connectionListener = connectionListener;
	}

	public void setDataMessageListener(@Nullable final DataMessageListener dataMessageListener) {
		this.dataMessageListener = dataMessageListener;
	}

	/**
	 * WebSocket connection successful opening handler
	 * You don't need to override this method, unless you want to change
	 * mClient's behaviour before connection
	 */
	protected void onOpen() {
		onWebSocketOpen();
		try {
			JSONObject jsonObject = new JSONObject();
			fillConnectionJSON(jsonObject);
			JSONArray messages = new JSONArray();
			messages.put(jsonObject);
			mClient.send(messages.toString());
		} catch (JSONException e) {
			logErrorWhen("during connection", e);
		}
	}

	public void onClose(final int code, final String reason, final boolean remote) {
		Timber.i("onClose: %d, %s, %b", code, reason, reason);
		onDisconnected(code, reason, remote);
	}

	/**
	 * Fills JSON with connection to centrifugo mInfo
	 * Derive this class and override this method to add custom fields to JSON object
	 *
	 * @param jsonObject connection message
	 * @throws JSONException thrown to indicate a problem with the JSON API
	 */
	protected void fillConnectionJSON(final JSONObject jsonObject)
			throws JSONException {
		jsonObject.put("uid", UUID.randomUUID().toString());
		jsonObject.put("method", "connect");

		JSONObject params = new JSONObject();
		params.put("user", mUserId);
		params.put("timestamp", mTokenTimestamp);
		params.put("info", mInfo);
		params.put("token", mToken);
		jsonObject.put("params", params);
	}

	protected void onWebSocketOpen() {
		if (connectionListener != null) {
			connectionListener.onWebSocketOpen();
		}
	}

	protected void onConnected() {
		if (connectionListener != null) {
			connectionListener.onConnected();
		}
	}

	protected void onDisconnected(final int code, final String reason, final boolean remote) {
		for (ActiveSubscription activeSubscription : subscribedChannels.values()) {
			activeSubscription.setConnected(false);
		}
		if (connectionListener != null) {
			connectionListener.onDisconnected(code, reason, remote);
		}
		//connection closed by remote host or was lost
		if (remote) {
//			if (reconnectConfig != null) {
//				//reconnect enabled
//				if (reconnectConfig.shouldReconnect()) {
//					reconnectConfig.incReconnectCount();
//					long reconnectDelay = reconnectConfig.getReconnectDelay();
//					scheduleReconnect(reconnectDelay);
//				}
//			}
		}
	}

	public void logErrorWhen(final String when, final Exception ex) {
		Timber.e(ex, "Error occurred %s", when);
	}

	public void onError(final Throwable ex) {
		Timber.e(ex, "On error");
	}

	protected void onSubscriptionError(@Nullable final String subscriptionError) {
		if (subscriptionListener != null) {
			subscriptionListener.onSubscriptionError(null, subscriptionError); //FIXME: rewrite using channel name
		}
	}

	protected void onSubscribedToChannel(@NonNull final String channelName) {
		if (subscriptionListener != null) {
			subscriptionListener.onSubscribed(channelName);
		}
	}

	protected void onNewMessage(final DataMessage dataMessage) {
		String uuid = dataMessage.getUUID();
		//update last message id
		ActiveSubscription activeSubscription = subscribedChannels.get(dataMessage.getChannel());
		if (activeSubscription != null) {
			activeSubscription.updateLastMessage(uuid);
		}
		if (dataMessageListener != null) {
			dataMessageListener.onNewDataMessage(dataMessage);
		}
	}

	protected void onLeftMessage(final LeftMessage leftMessage) {
		if (joinLeaveListener != null) {
			joinLeaveListener.onLeave(leftMessage);
		}
	}

	protected void onJoinMessage(final JoinMessage joinMessage) {
		if (joinLeaveListener != null) {
			joinLeaveListener.onJoin(joinMessage);
		}
	}

	public void subscribe(@NonNull final SubscriptionRequest subscriptionRequest) {
		subscribe(subscriptionRequest, null);
	}

	public void subscribe(final SubscriptionRequest subscriptionRequest, @Nullable final String lastMessageId) {
		if (!mClient.isConnected()) {
			channelsToSubscribe.add(subscriptionRequest);
		}
		try {
			JSONObject jsonObject = new JSONObject();
			String uuid = fillSubscriptionJSON(jsonObject, subscriptionRequest, lastMessageId);
			commandListeners.put(uuid, new DownstreamMessageListener() {
				@Override
				public void onDownstreamMessage(final DownstreamMessage message) {
					SubscribeMessage subscribeMessage = (SubscribeMessage) message;
					String subscriptionError = subscribeMessage.getError();
					if (subscriptionError != null) {
						onSubscriptionError(subscriptionError);
						return;
					}
					String channelName = subscribeMessage.getChannel();
					Boolean status = subscribeMessage.getStatus();
					if (status != null && status) {
						if (channelName != null) {
							ActiveSubscription activeSubscription;
							String channel = subscriptionRequest.getChannel();
							if (subscribedChannels.containsKey(channel)) {
								activeSubscription = subscribedChannels.get(channel);
							} else {
								activeSubscription = new ActiveSubscription(subscriptionRequest);
								subscribedChannels.put(channel, activeSubscription);
							}
							//mark as connected
							activeSubscription.setConnected(true);
							onSubscribedToChannel(channelName);
						}
					}
					JSONArray recoveredMessages = subscribeMessage.getRecoveredMessages();
					if (recoveredMessages != null) {
						for (int i = 0; i < recoveredMessages.length(); i++) {
							JSONObject messageObj = recoveredMessages.optJSONObject(i);
							DataMessage dataMessage = DataMessage.fromBody(messageObj);
							onNewMessage(dataMessage);
						}
					}
				}
			});
			JSONArray messages = new JSONArray();
			messages.put(jsonObject);

			mClient.send(messages.toString());
		} catch (JSONException e) {
			logErrorWhen("during subscription", e);
		}
	}

	/**
	 * Fills JSON with subscription mInfo
	 * Derive this class and override this method to add custom fields to JSON object
	 *
	 * @param jsonObject          subscription message
	 * @param subscriptionRequest request for subscription
	 * @param lastMessageId       id of last message
	 * @return uid of this command
	 * @throws JSONException thrown to indicate a problem with the JSON API
	 */
	protected String fillSubscriptionJSON(final JSONObject jsonObject, final SubscriptionRequest subscriptionRequest, @Nullable final String lastMessageId)
			throws JSONException {
		String uuid = UUID.randomUUID().toString();
		jsonObject.put("uid", uuid);
		jsonObject.put("method", "subscribe");
		JSONObject params = new JSONObject();
		String channel = subscriptionRequest.getChannel();
		params.put("channel", channel);
		if (channel.startsWith(PRIVATE_CHANNEL_PREFIX)) {
			params.put("sign", subscriptionRequest.getChannelToken());
			params.put("mClient", mClientId);
			params.put("mInfo", subscriptionRequest.getInfo());
		}
		if (lastMessageId != null) {
			params.put("last", lastMessageId);
			params.put("recover", true);
		}
		jsonObject.put("params", params);
		return uuid;
	}

	/**
	 * Handler for messages, that does the routine of subscribing
	 * and sending messages in the broadcasts
	 * Only apps with permission YOUR_PACKAGE_ID.permission.CENTRIFUGO_PUSH
	 * (e.g. com.example.testapp.permission.CENTRIFUGO_PUSH)
	 * signed with your developer key can receive your push
	 * Filter for broadcasts must be YOUR_PACKAGE_ID.action.CENTRIFUGO_PUSH
	 * (e.g. com.example.testapp.action.CENTRIFUGO_PUSH)
	 * You don't need to override this method, unless you want to change
	 * mClient's behaviour after connection and before subscription
	 *
	 * @param message message to handle
	 */
	protected void onMessage(@NonNull final JSONObject message) {
		String method = message.optString("method", "");
		if (method.equals("connect")) {
			JSONObject body = message.optJSONObject("body");
			if (body != null) {
				this.mClientId = body.optString("mClient");
			}

			for (SubscriptionRequest subscriptionRequest : channelsToSubscribe) {
				subscribe(subscriptionRequest);
			}
			channelsToSubscribe.clear();
			for (ActiveSubscription activeSubscription : subscribedChannels.values()) {
				subscribe(activeSubscription.getInitialRequest(), activeSubscription.getLastMessageId());
			}
			onConnected();
			return;
		}
		if (method.equals("subscribe")) {
			SubscribeMessage subscribeMessage = new SubscribeMessage(message);
			String uuid = subscribeMessage.getRequestUUID();
			DownstreamMessageListener listener = commandListeners.get(uuid);
			if (listener != null) {
				listener.onDownstreamMessage(subscribeMessage);
			}
			return;
		}
		if (method.equals("join")) {
			JoinMessage joinMessage = new JoinMessage(message);
			onJoinMessage(joinMessage);
			return;
		}
		if (method.equals("leave")) {
			LeftMessage leftMessage = new LeftMessage(message);
			onLeftMessage(leftMessage);
			return;
		}
		if (method.equals("presence")) {
			PresenceMessage presenceMessage = new PresenceMessage(message);
			String uuid = presenceMessage.getRequestUUID();
			DownstreamMessageListener listener = commandListeners.get(uuid);
			if (listener != null) {
				listener.onDownstreamMessage(presenceMessage);
			}
			return;
		}
		if (method.equals("history")) {
			HistoryMessage historyMessage = new HistoryMessage(message);
			String uuid = historyMessage.getRequestUUID();
			DownstreamMessageListener listener = commandListeners.get(uuid);
			if (listener != null) {
				listener.onDownstreamMessage(historyMessage);
			}
			return;
		}
		DataMessage dataMessage = new DataMessage(message);
		onNewMessage(dataMessage);
	}

	public Future<HistoryMessage> requestHistory(final String channelName) {
		JSONObject jsonObject = new JSONObject();
		String commandId = UUID.randomUUID().toString();
		try {
			jsonObject.put("uid", commandId);
			jsonObject.put("method", "history");
			JSONObject params = new JSONObject();
			params.put("channel", channelName);
			jsonObject.put("params", params);
		} catch (JSONException e) {
			//FIXME error handling
		}
		final Future<HistoryMessage> historyMessage = new Future<>();
		//don't let block mClient's thread
		//historyMessage.setRestrictedThread(mClient.);
		// todo: check
		commandListeners.put(commandId, new DownstreamMessageListener() {
			@Override
			public void onDownstreamMessage(final DownstreamMessage message) {
				historyMessage.setData((HistoryMessage) message);
			}
		});
		mClient.send(jsonObject.toString());
		return historyMessage;
	}

	public Future<PresenceMessage> requestPresence(final String channelName) {
		JSONObject jsonObject = new JSONObject();
		String commandId = UUID.randomUUID().toString();
		try {
			jsonObject.put("uid", commandId);
			jsonObject.put("method", "presence");
			JSONObject params = new JSONObject();
			params.put("channel", channelName);
			jsonObject.put("params", params);
		} catch (JSONException e) {
			//FIXME error handling
		}
		final Future<PresenceMessage> presenceMessage = new Future<>();
		//don't let block mClient's thread
		// todo: check
//		presenceMessage.setRestrictedThread(mClient.getClientThread());
		commandListeners.put(commandId, message -> presenceMessage.setData((PresenceMessage) message));
		mClient.send(jsonObject.toString());
		return presenceMessage;
	}

	private void scheduleReconnect(@IntRange(from = 0) final long delay) {
		final Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					connect();
					timer.cancel();
					this.cancel();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (NoSuchAlgorithmException e) {
					e.printStackTrace();
				}
			}
		}, delay);
	}

	public void setReconnectConfig(@Nullable final ReconnectConfig reconnectConfig) {
		this.reconnectConfig = reconnectConfig;
	}

	public static class Builder {

		@NonNull
		private String wsURI;

		private User user;

		private Token token;

		@Nullable
		private String info;

		@Nullable
		private ReconnectConfig reconnectConfig;
		private boolean mDebug = false;

		public Builder(@NonNull final String wsURI) {
			this.wsURI = wsURI;
		}
		public Builder setToken(@NonNull final Token token) {
			this.token = token;
			return this;
		}
		public Builder setUser(@NonNull final User user) {
			this.user = user;
			return this;
		}
		public Builder setInfo(@Nullable final String info) {
			this.info = info;
			return this;
		}
		public Builder setReconnectConfig(@Nullable final ReconnectConfig reconnectConfig) {
			this.reconnectConfig = reconnectConfig;
			return this;
		}
		public Builder setDebug(boolean debug) {
			mDebug = debug;
			return this;
		}

		public Centrifugo build() {
			if (user == null) {
				throw new NullPointerException("user mInfo not provided");
			}
			if (token == null) {
				throw new NullPointerException("mToken not provided");
			}
			Centrifugo centrifugo;
			try {
				centrifugo = new Centrifugo(wsURI, user.getUser(), user.getClient(), token.getToken(), token.getTokenTimestamp(), info);
				centrifugo.setDebug(mDebug);
			} catch (IOException e) {
				throw new RuntimeException(e);
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
			centrifugo.setReconnectConfig(reconnectConfig);
			return centrifugo;
		}

	}

}
