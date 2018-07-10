package com.centrifugal.centrifuge.android.client;

import com.neovisionaries.ws.client.ThreadType;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketCloseCode;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketListener;
import com.neovisionaries.ws.client.WebSocketState;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import io.reactivex.disposables.Disposable;
import timber.log.Timber;

/**
 * Dogsy. 2017
 *
 * @author Eduard Maximovich <edward.vstock@gmail.com>
 */
final class WebsocketEventHandler implements WebSocketListener {
	private WeakReference<WebsocketClient> mService;
	private Disposable mQueueDisposable;

	WebsocketEventHandler(WebsocketClient service) {
		mService = new WeakReference<>(service);
	}

	@Override
	public void onStateChanged(WebSocket websocket, WebSocketState newState) {
		if (newState == WebSocketState.CONNECTING) {
			getLog().d("[%s] Connecting...", Thread.currentThread().getName());
			callOnStartConnecting();
		}

		if (newState == WebSocketState.OPEN && !mService.get().mConnected) {
			mService.get().mConnected = true;
			getLog().d("[%s] Connected!", Thread.currentThread().getName());
			if (mQueueDisposable != null && !mQueueDisposable.isDisposed()) {
				mQueueDisposable.dispose();
			}
			mQueueDisposable = mService.get().mMessageQueue.observe()
					.doOnSubscribe(d -> mService.get().mMessageDisposable = d)
					.subscribe(msgBuilder -> mService.get().sendInternal(msgBuilder));

			callOnFinishConnecting();
		}

		if (newState == WebSocketState.CLOSING) {
			mService.get().mConnected = false;
			getLog().d("[%s] Closing connection...", Thread.currentThread().getName());
			if (mService.get().mMessageDisposable != null &&
					!mService.get().mMessageDisposable.isDisposed()) {
				getLog().w("Disposing message queue on closing connection");
				mService.get().mMessageDisposable.dispose();
				if (mQueueDisposable != null && !mQueueDisposable.isDisposed()) {
					mQueueDisposable.dispose();
				}
			}
		}

		if (newState == WebSocketState.CLOSED) {
			getLog().d("[%s] Connection is closed.", Thread.currentThread().getName());
		}
	}

	@Override
	public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
	}

	@Override
	public void onConnectError(WebSocket websocket, WebSocketException cause)
			throws Exception {
		callOnError(websocket, cause);
		Thread.sleep(3000);

		if (websocket.getState() != WebSocketState.CONNECTING) {
			mService.get().reconnect();
		}
	}

	@Override
	public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
		boolean startedReconnecting = false;

		if(closedByServer) {
			if(serverCloseFrame != null) {
				callOnClosedConnection(true, serverCloseFrame.getCloseCode(), serverCloseFrame.getCloseReason());
			} else {
				callOnClosedConnection(true, WebSocketCloseCode.ABNORMAL, "");
			}
		} else {
			if(clientCloseFrame != null) {
				callOnClosedConnection(false, clientCloseFrame.getCloseCode(), clientCloseFrame.getCloseReason());
			} else {
				callOnClosedConnection(false, WebSocketCloseCode.ABNORMAL, "");
			}
		}

		if (serverCloseFrame != null) {
			getLog().d("WS closed by server: %b, %s", closedByServer, serverCloseFrame.getCloseReason());
			if(serverCloseFrame.getCloseReason() != null) {
				try {
					JSONObject reasonData = new JSONObject(serverCloseFrame.getCloseReason());
					if(reasonData.has("reconnect") && !reasonData.getBoolean("reconnect")) {
						return;
					}
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
			if (mService.get().mRecoverableServerStatuses.contains(serverCloseFrame.getCloseCode()) &&
					websocket.getState() != WebSocketState.CONNECTING) {
				try {
					Thread.sleep(1000);
					getLog().d("Reconnecting (closed by server)");
					mService.get().reconnect();
					startedReconnecting = true;
				} catch (InterruptedException e) {
				}
			}
		}

		if (clientCloseFrame != null) {
			getLog().d("Client close[%d]: %s", clientCloseFrame.getCloseCode(), clientCloseFrame.getCloseReason());
			if (mService.get().mRecoverableClientStatuses.contains(clientCloseFrame.getCloseCode())) {
				if (!startedReconnecting) {
					try {
						Thread.sleep(1000);
						mService.get().reconnect();
						getLog().d("Reconnecting (closed by client)");
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}

	@Override
	public void onFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onContinuationFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onTextFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onBinaryFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onCloseFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onPingFrame(WebSocket websocket, WebSocketFrame frame) {
		websocket.sendPong(frame.getPayload());
	}

	@Override
	public void onPongFrame(WebSocket websocket, WebSocketFrame frame) {
	}

	@Override
	public void onTextMessage(WebSocket websocket, String text) {
		getLog().d("OnTextMessage: %s", text);
		callOnMessage(websocket, text);
	}

	@Override
	public void onBinaryMessage(WebSocket websocket, byte[] binary) {
		getLog().d("OnBinaryMessage: length=", binary.length);
		final StringBuilder sb = new StringBuilder();
		for (byte b : binary) {
			sb.append(b);
		}

		callOnMessage(websocket, sb.toString());
	}

	@Override
	public void onSendingFrame(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onFrameSent(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onFrameUnsent(WebSocket websocket, WebSocketFrame frame) {

	}

	@Override
	public void onThreadCreated(WebSocket websocket, ThreadType threadType, Thread thread) {
	}

	@Override
	public void onThreadStarted(WebSocket websocket, ThreadType threadType, Thread thread) {
	}

	@Override
	public void onThreadStopping(WebSocket websocket, ThreadType threadType, Thread thread) {
	}

	@Override
	public void onError(WebSocket websocket, WebSocketException cause) {
		callOnError(websocket, cause);
	}

	@Override
	public void onFrameError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) {
		callOnError(websocket, cause);
	}

	@Override
	public void onMessageError(WebSocket websocket, WebSocketException cause, List<WebSocketFrame> frames) {
		callOnError(websocket, cause);
	}

	@Override
	public void onMessageDecompressionError(WebSocket websocket, WebSocketException cause, byte[] compressed) {
		callOnError(websocket, cause);
	}

	@Override
	public void onTextMessageError(WebSocket websocket, WebSocketException cause, byte[] data) {
		callOnError(websocket, cause);
	}

	@Override
	public void onSendError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) {
		callOnError(websocket, cause);
	}

	@Override
	public void onUnexpectedError(WebSocket websocket, WebSocketException cause) {
		callOnError(websocket, cause);
	}

	@Override
	public void handleCallbackError(WebSocket websocket, Throwable cause) {
		callOnError(websocket, cause);
	}

	@Override
	public void onSendingHandshake(WebSocket websocket, String requestLine, List<String[]> headers) {

	}

	private Timber.Tree getLog() {
		return mService.get().getLog();
	}

	private void callOnStartConnecting() {
		if (mService.get() != null && mService.get().mOnConnectingListener != null) {
			mService.get().mOnConnectingListener.onStart();
		}
	}

	private void callOnClosedConnection(boolean byServer, int code, String reason) {
		if(mService.get() != null && mService.get().mOnCloseListener != null) {
			mService.get().mOnCloseListener.onClose(code, reason, byServer);
		}
	}

	private void callOnFinishConnecting() {
		if (mService.get() != null && mService.get().mOnConnectingListener != null) {
			mService.get().mOnConnectingListener.onFinish();
		}
	}

	private void callOnError(WebSocket ws, Throwable ex) {
		getLog().w(ex);
		if (mService.get().mMessageDisposable != null &&
				!mService.get().mMessageDisposable.isDisposed()) {
			getLog().w("Disposing message queue on error");
			mService.get().mMessageDisposable.dispose();
		}
		if (mService.get().mOnErrorListener != null) {
			mService.get().mOnErrorListener.onError(ws, ex);
		}

	}

	private void callOnMessage(WebSocket ws, String msg) {
		if (mService.get() != null && mService.get().mOnMessageListener != null) {
			mService.get().mOnMessageListener.onMessage(ws, msg);
		}
	}
}
