package com.centrifugal.centrifuge.android.client;

import android.support.annotation.NonNull;

import com.centrifugal.centrifuge.android.message.MessageQueue;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketCloseCode;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketState;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;

import io.reactivex.disposables.Disposable;
import timber.log.Timber;

import static com.google.common.base.Preconditions.checkNotNull;


/**
 * wsclient. 2017
 *
 * @author Eduard Maximovich <edward.vstock@gmail.com>
 */
public final class WebsocketClient {
	/**
	 * Статусы, при которых будем пытаться переподключаться, при иных не имеет смысла
	 */
	final List<Integer> mRecoverableServerStatuses = new ArrayList<Integer>() {{
		add(WebSocketCloseCode.NORMAL);
		add(WebSocketCloseCode.AWAY);
		add(WebSocketCloseCode.UNCONFORMED);
		add(WebSocketCloseCode.UNEXPECTED);
		add(WebSocketCloseCode.INCONSISTENT);
		add(WebSocketCloseCode.VIOLATED);
		add(WebSocketCloseCode.OVERSIZE);//message to big
	}};

	final List<Integer> mRecoverableClientStatuses = new ArrayList<Integer>() {{
		add(WebSocketCloseCode.AWAY);
		add(WebSocketCloseCode.UNCONFORMED);
		add(WebSocketCloseCode.VIOLATED);
	}};

	final MessageQueue<String> mMessageQueue = new MessageQueue<>();
	OnMessageListener mOnMessageListener;
	OnErrorListener mOnErrorListener;
	OnConnectingListener mOnConnectingListener;
	OnCloseListener mOnCloseListener;
	Disposable mMessageDisposable;
	boolean mConnected = false;
	private WebSocket mWebSocket;
	private WebsocketEventHandler mListener;
	private int mTimeout = 10000;
	private String mAddress;
	private short mPort = 443;
	private boolean mSecure = false;
	private String mEndpoint = "/";
	private String mFullEndpoint;

	public WebsocketClient(String remoteAddress, String endpoint, short port, boolean secure) {
		mAddress = remoteAddress;
		mEndpoint = endpoint;
		mPort = port;
		mSecure = secure;
	}


	public WebsocketClient(String fullEndpoint) {
		mFullEndpoint = fullEndpoint;
	}

	public WebsocketClient(String remoteAddress, String endpoint, short port) {
		this(remoteAddress, endpoint, port, false);
	}

	public WebsocketClient(String remoteAddress, String endpoint) {
		this(remoteAddress, endpoint, (short) 8085, false);
	}

	public WebsocketClient performConnection()
			throws NoSuchAlgorithmException, IOException {
		getLog().d("Perform connection");
		if (mWebSocket != null && mWebSocket.getState() != WebSocketState.CREATED) {
			mWebSocket.recreate();
			getLog().d("Recreating connection");
			return this;
		}
		SSLContext ctx = NaiveSSLContext.getInstance("TLS");
		WebSocketFactory factory = new WebSocketFactory();
		factory.setSSLContext(ctx);
		factory.setVerifyHostname(false);
		factory.setConnectionTimeout(mTimeout);

		mListener = new WebsocketEventHandler(this);

		if (mFullEndpoint != null) {
			mWebSocket = factory.createSocket(mFullEndpoint, mTimeout);
		} else {
			if (mEndpoint == null || mEndpoint.isEmpty()) {
				mEndpoint = "/";
			}

			final String host = String.format(Locale.getDefault(), "%s://%s:%d%s",
					mSecure ? "wss" : "ws",
					mAddress,
					mPort,
					mEndpoint
			);

			mWebSocket = factory.createSocket(host, mTimeout);
		}

		mWebSocket.addListener(mListener);

		return this;
	}

	public boolean isConnected() {
		return mConnected || (mWebSocket != null && mWebSocket.isOpen());
	}

	/**
	 * @param timeoutMs Milliseconds
	 */
	public void setConnectionTimeout(int timeoutMs) {
		mTimeout = timeoutMs;
	}

	public WebSocket getConnection() {
		return mWebSocket;
	}

	public void setOnMessageListener(@NonNull OnMessageListener listener) {
		checkNotNull(listener, "Message listener can't be null");
		mOnMessageListener = listener;
	}

	public void setOnErrorListener(@NonNull OnErrorListener listener) {
		checkNotNull(listener, "Error listener can't be null");
		mOnErrorListener = listener;
	}

	public void setOnConnectingListener(@NonNull OnConnectingListener listener) {
		checkNotNull(listener, "Connecting listener can't be null");
		mOnConnectingListener = listener;
	}

	public void setOnCloseListener(@Nonnull OnCloseListener listener) {
		mOnCloseListener = listener;
	}

	public void clearListeners() {
		mOnMessageListener = null;
		mOnErrorListener = null;
		mOnConnectingListener = null;
	}

	public void disconnect() {
		if (mWebSocket != null && mWebSocket.isOpen()) {
			getLog().d("Disconnecting with code: %d", WebSocketCloseCode.NORMAL);
			mWebSocket.disconnect();
		}
	}

	public void connectSync()
			throws IOException, NoSuchAlgorithmException, WebSocketException {
		performConnection();
		mWebSocket.connect();
	}

	public void connect()
			throws IOException, NoSuchAlgorithmException {
		performConnection();
		mWebSocket.connectAsynchronously();
	}

	public void send(MessageQueue.SendListener<String> onSend, String... messages) {
		send(Arrays.asList(messages), onSend);
	}

	public void send(String... messageBuilders) {
		send(Arrays.asList(messageBuilders), null);
	}

	public void send(Collection<String> messageBuilder) {
		send(messageBuilder, null);
	}

	public void send(Collection<String> messageBuilder, MessageQueue.SendListener<String> onSend) {
		getLog().d("Add messages %d to queue", messageBuilder.size());
		mMessageQueue.addAll(messageBuilder, onSend);
	}

	public void send(String messageBuilder) {
		getLog().d("Add message to queue");
		mMessageQueue.add(messageBuilder, null);
	}

	public void send(String messageBuilder, MessageQueue.SendListener<String> onSend) {
		getLog().d("Add message to queue (with callback)");
		mMessageQueue.add(messageBuilder, onSend);
	}

	void sendInternal(MessageQueue.MetaMessage<String> metaMessage) {
		getLog().d("Dequeue message and send to server: %s", metaMessage.getMessage());
		mMessageQueue.poll();

		try {
			mWebSocket.sendText(metaMessage.getMessage());
			if (metaMessage.getSendListener() != null) {
				metaMessage.getSendListener().onSend(metaMessage.getMessage());
			}

		} catch (Throwable e) {
			getLog().w(e, "Can't send message");
		}
	}

	Timber.Tree getLog() {
		return Timber.tag("ChatService");
	}

	protected void reconnect() {
		try {
			mWebSocket.removeListener(mListener);
			mWebSocket = mWebSocket.recreate();
			mWebSocket.addListener(mListener);
			mWebSocket.connect();
		} catch (IOException e) {
			getLog().w(e, "Reconnection error");
		} catch (WebSocketException e) {
			try {
				Thread.sleep(1000);
				reconnect();
			} catch (InterruptedException e1) {
				e1.printStackTrace();
				getLog().w(e, "Reconnection error (interrupted!)");
			}
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			disconnect();
		} catch (Throwable ignore) {
		}

		super.finalize();
	}

	public interface OnConnectingListener {
		void onStart();
		void onFinish();
	}

	public interface OnCloseListener {
		void onClose(int code, String reason, boolean byServer);
	}

	public interface OnErrorListener {
		void onError(WebSocket websocket, Throwable cause);
	}

	public interface OnMessageListener {
		void onMessage(WebSocket ws, String message);
	}
}
