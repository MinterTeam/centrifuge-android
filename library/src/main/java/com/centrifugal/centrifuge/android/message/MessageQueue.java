package com.centrifugal.centrifuge.android.message;

import com.annimon.stream.Stream;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;

import io.reactivex.subjects.ReplaySubject;

/**
 * Dogsy. 2017
 *
 * @author Eduard Maximovich <edward.vstock@gmail.com>
 * <p>
 * ui -> send(msg) mq -> push_back(msg) mq -> tell(msg) -> subject received (msg) ws ->
 * send
 * <p>
 * -- start send cycle ws -> onSuccess -> mq.pop() ws -> onError -> mq -> unsubcribe -> {do
 * nothing, leave message in queue} , so how to resend? ws -> onReconnected -> observe(msg
 * -> ws.send(msg)) -- repeat send cycle
 */

public class MessageQueue<T> extends LinkedBlockingQueue<T> {

	private final static Object sLock = new Object();
	private final ReplaySubject<MetaMessage<T>> mNotifier = ReplaySubject.createWithSize(1);

	public boolean addAll(Collection<? extends T> c, SendListener<T> callback) {
		boolean added = super.addAll(c);
		if (added) {
			Stream.of(c).forEach(item -> tell(item, callback));
		}

		return added;
	}

	public boolean add(T t, SendListener<T> callback) {
		boolean added = super.add(t);
		if (added) tell(t, callback);
		return added;
	}

	public ReplaySubject<MetaMessage<T>> observe() {
		return mNotifier;
	}

	private void tell(T t, SendListener<T> callback) {
		synchronized (sLock) {
			mNotifier.onNext(new MetaMessage<>(t, callback));
		}
	}

	public interface SendListener<T> {
		void onSend(T message);
	}

	public static class MetaMessage<T> {
		private T mMessage;
		private SendListener<T> mSendListener;

		MetaMessage(T message, SendListener<T> callback) {
			mMessage = message;
			mSendListener = callback;
		}

		public T getMessage() {
			return mMessage;
		}

		public SendListener<T> getSendListener() {
			return mSendListener;
		}
	}
}
