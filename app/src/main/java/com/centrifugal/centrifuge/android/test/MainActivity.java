package com.centrifugal.centrifuge.android.test;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.centrifugal.centrifuge.android.Centrifugo;
import com.centrifugal.centrifuge.android.config.ReconnectConfig;
import com.centrifugal.centrifuge.android.credentials.Token;
import com.centrifugal.centrifuge.android.credentials.User;
import com.centrifugal.centrifuge.android.listener.ConnectionListener;
import com.centrifugal.centrifuge.android.listener.DataMessageListener;
import com.centrifugal.centrifuge.android.listener.JoinLeaveListener;
import com.centrifugal.centrifuge.android.listener.SubscriptionListener;
import com.centrifugal.centrifuge.android.message.DataMessage;
import com.centrifugal.centrifuge.android.message.presence.JoinMessage;
import com.centrifugal.centrifuge.android.message.presence.LeftMessage;
import com.centrifugal.centrifuge.android.subscription.SubscriptionRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Array;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private EditText addressEditText;

    private EditText userNameEditText;

    private EditText channelEditText;

    private ListView listView;

    private List<DataMessage> dataMessageList = new ArrayList<>();
    private Adapter stringArrayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        findViewById(R.id.login).setOnClickListener(v -> login());
    }

    private Centrifugo centrifugo;

    private void login() {
        new Thread() {
            @Override
            public void run() {

                //channel=Y4OTszZjM5ZjgzMTAwOG, token=3f39f831008af6af0ed9831992c3c96f257a21d6fd0fff675a3b88b5652ab798, timestamp=1531150689
//                String userId = loginObject.optString("userId");
//                String timestamp = loginObject.optString("timestamp");
//                String token = loginObject.optString("token");
//                String centrifugoAddress = loginObject.optString("centrifugoWS");
/*
	            "data": {
		            "channel": "MTIzMzQ1MjtlYjdjYmU3",
				            "timestamp": 1531233452,
				            "token": "eb7cbe7dca732f58e572178dd2d172232aa77039fbf875893dd93d4b78575d2f"
	            }
*/

                centrifugo = new Centrifugo.Builder("ws://92.53.87.98:8000/connection/websocket")
		                .setReconnectConfig(new ReconnectConfig(10, 3, TimeUnit.SECONDS))
		                .setUser(new User("0", "e1d78ee4f65fdec2cb2aab88e53083938d00879f73562b9bffcaf70bda867c8e"))
		                .setToken(new Token("e1d78ee4f65fdec2cb2aab88e53083938d00879f73562b9bffcaf70bda867c8e", "1531233706"))
		                .setDebug(true)
		                .build();
                centrifugo.subscribe(new SubscriptionRequest("zMzcwNjtlMWQ3OGVlNGY"));
                centrifugo.setConnectionListener(new ConnectionListener() {
                    @Override
                    public void onWebSocketOpen() {

                    }

                    @Override
                    public void onConnected() {
                        message("Connected to Centrifugo!", "Now subscribe to channel");
                    }

                    @Override
                    public void onDisconnected(final int code, final String reason, final boolean remote) {
                        message("Disconnected from Centrifugo.", "Bye-bye");
                    }
                });
                centrifugo.setSubscriptionListener(new SubscriptionListener() {
                    @Override
                    public void onSubscribed(final String channelName) {
                        message("Just subscribed to " + channelName, "Awaiting messages");
                    }

                    @Override
                    public void onUnsubscribed(final String channelName) {
                        message("Unsubscribed from " + channelName, "Bye");
                    }

                    @Override
                    public void onSubscriptionError(final String channelName, final String error) {
                        error("Failed to subscribe to " + channelName + ", cause: " + error);
                    }

                });
                centrifugo.setDataMessageListener(new DataMessageListener() {
                    @Override
                    public void onNewDataMessage(final DataMessage message) {
                        showMessage(message);
                    }
                });
                centrifugo.setJoinLeaveListener(new JoinLeaveListener() {
                    @Override
                    public void onJoin(final JoinMessage joinMessage) {
                        message(joinMessage.getUser(), " just joined " + joinMessage.getChannel());
                    }

                    @Override
                    public void onLeave(final LeftMessage leftMessage) {
                        message(leftMessage.getUser(), " just left " + leftMessage.getChannel());
                    }
                });
	            try {
		            centrifugo.connect();
	            } catch (IOException e) {
		            e.printStackTrace();
	            } catch (NoSuchAlgorithmException e) {
		            e.printStackTrace();
	            }
            }
        }.start();
    }

    @Override
    protected void onStop() {
        if (centrifugo != null) {
            centrifugo.disconnect();
        }
        super.onStop();
    }

    private void showMessage(final DataMessage message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stringArrayAdapter.add(message);
            }
        });
    }

    private class Adapter extends BaseAdapter<Holder, DataMessage> {

        private List<DataMessage> dataMessages = new ArrayList<>();

        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

        public Adapter() {
            super(MainActivity.this, android.R.layout.simple_list_item_2);
        }

        @Override
        public int getCount() {
            return dataMessages.size();
        }

        public void add(final DataMessage dataMessage) {
            dataMessages.add(dataMessage);
            stringArrayAdapter.notifyDataSetChanged();
        }

        @Override
        public void onBindViewHolder(final Holder holder, final int position) {
            DataMessage dataMessage = dataMessages.get(position);
            String date = sdf.format(dataMessage.getTimestamp());
            String channel = dataMessage.getChannel();
            String upper = date + " [" + channel + "]";
            String lower = "";
            String data = dataMessage.getData();
            if (data != null) {
                lower = data;
            }
            holder.tv1.setText(upper);
            holder.tv2.setText(lower);
        }

        @Override
        public Holder onCreateViewHolder(final ViewGroup viewGroup, final int position) {
            View v = LayoutInflater.from(viewGroup.getContext()).inflate(android.R.layout.simple_list_item_2, viewGroup, false);
            return new Holder(v);
        }

    }

    private class Holder extends BaseAdapter.BaseHolder {

        TextView tv1;
        TextView tv2;

        protected Holder(final View view) {
            super(view);
            tv1 = findViewById(android.R.id.text1);
            tv2 = findViewById(android.R.id.text2);
        }
    }

    private void error(final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Snackbar
                        .make(findViewById(R.id.main), "Error: " + error, Snackbar.LENGTH_LONG)
                        .show();
            }
        });
    }

    private void message(final String messageMain, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
	            Log.d(messageMain, message);
                SpannableStringBuilder snackbarText = new SpannableStringBuilder();
                snackbarText.append(messageMain);
                snackbarText.setSpan(new ForegroundColorSpan(0xFF00DD00), 0, snackbarText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                snackbarText.append(" ").append(message);
                Snackbar.make(findViewById(R.id.main), snackbarText, Snackbar.LENGTH_LONG).show();
            }
        });
    }

}
