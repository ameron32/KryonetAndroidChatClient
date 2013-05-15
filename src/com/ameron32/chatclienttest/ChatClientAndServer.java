package com.ameron32.chatclienttest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import com.ameron32.chatclienttest.ChatClientAndServer.ChatClient.ChatFrame;
import com.ameron32.chatclienttest.Network.ChatMessage;
import com.ameron32.chatclienttest.Network.RegisterName;
import com.ameron32.chatclienttest.Network.UpdateNames;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

/**
 * @author klemeilleur
 * I've adapted the java ChatClient.java, not the RMI one, to android here.
 * Do to the nature of wanting to allow peer-to-peer chat, unlike the Java example, 
 * I needed to be able to be the server and client simultaneously, from the same
 * application. I couldn't figure out how to do that without merging them into one
 * activity. A more experienced programmer could have done it, I have no doubt.
 * 
 * This has been tested to work connecting with both a Java server on Windows
 * (local wifi as well as internet) and with other Android devices 
 * across the same peer-to-peer wifi network. Not tested as an Android server
 * with Java clients connecting to it or peer-to-peer (all Android)
 * across the internet.
 * 
 * Change, improve, do whatever. Enjoy!
 */
public class ChatClientAndServer extends Activity implements OnClickListener {

	ChatServer cs;
	ChatClient cc;

	Server server;

	ChatFrame chatFrame;
	Client client;
	public String host = "localhost";
	public String name = "user";
	
	// your session and your username are remembered 
	// after closing or pausing the app.
	// it will attempt to reconnect to the last host automatically
	// when opened. (my friends were complaining about having to type it
	// everytime) :)
	SharedPreferences savedName, savedHost;

	AlertDialog dialogHost, dialogName;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		com.esotericsoftware.minlog.Log
				.set(com.esotericsoftware.minlog.Log.LEVEL_DEBUG);
	}

	@Override
	protected void onResume() {
		super.onResume();
		savedName = getSharedPreferences(name, 0);
		savedHost = getSharedPreferences(host, 0);
		String userNameReturned = savedName.getString("UserName", name);
		name = userNameReturned;
		String hostReturned = savedHost.getString("Host", host);
		host = hostReturned;
		connect();
	}

	@Override
	protected void onPause() {
		chatFrame.setOnPauseListener(new Runnable() {
			public void run() {
				client.stop();
			}
		});
		super.onPause();
	}

	@Override
	protected void onStop() {
		stopLocalServer();
		super.onStop();
	}

	public void connect() {
		if (host == "localhost") {
			startLocalServer();
		} else {
			stopLocalServer();
		}
		cc = new ChatClient();
		chatFrame.onResume();
	}

	public void startLocalServer() {
		if (!isLocalServerRunning()) {
			try {
				cs = new ChatServer();
			} catch (IOException e) {
				e.printStackTrace();
				cc.setErrorToastListener("Failed to start local server.");
			}
		}
	}

	public void stopLocalServer() {
		if (isLocalServerRunning()) {
			chatFrame.setOnStopListener(new Runnable() {
				public void run() {
					cs.stopServer();
					cs = null;
				}
			});
		}
	}

	public boolean isLocalServerRunning() {
		if (cs == null) {
			return false;
		}
		return true;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}

	public class ChatServer {

		public ChatServer() throws IOException {
			server = new Server() {
				protected Connection newConnection() {
					// By providing our own connection implementation,
					// we can store per connection state
					// without a connection ID to state look up.
					return new ChatConnection();
				}
			};

			// For consistency, the classes to be sent over the network are
			// registered by the same method for both the client and server.
			Network.register(server);

			server.addListener(new Listener() {
				public void received(Connection c, Object object) {
					// We know all connections for this server are actually
					// ChatConnections.
					ChatConnection connection = (ChatConnection) c;

					if (object instanceof RegisterName) {
						// Ignore the object if a client
						// has already registered a name. This is
						// impossible with our client, but
						// a hacker could send messages at any time.
						if (connection.name != null)
							return;
						// Ignore the object if the name is invalid.
						String name = ((RegisterName) object).name;
						if (name == null)
							return;
						name = name.trim();
						if (name.length() == 0)
							return;
						// Store the name on the connection.
						connection.name = name;
						// Send a "connected" message to everyone
						// except the new client.
						ChatMessage chatMessage = new ChatMessage();
						chatMessage.text = name + " connected.";
						server.sendToAllExceptTCP(connection.getID(),
								chatMessage);
						// Send everyone a new list of connection names.
						updateNames();
						return;
					}

					if (object instanceof ChatMessage) {
						// Ignore the object if a client tries to chat before
						// registering a name.
						if (connection.name == null) {
							final String fail = connection.name
									+ ": connection.name == null";
							cc.setErrorToastListener(fail);
							return;
						}
						ChatMessage chatMessage = (ChatMessage) object;
						// Ignore the object if the chat message is invalid.
						String message = chatMessage.text;
						if (message == null) {
							final String fail = connection.name
									+ ": message == null";
							cc.setErrorToastListener(fail);
							return;
						}

						message = message.trim();
						if (message.length() == 0) {
							final String fail = connection.name
									+ ": message.length() == 0" + "\n"
									+ "----message was [" + message + "]";
							cc.setErrorToastListener(fail);
							return;
						}
						// Prepend the connection's name and send to everyone.
						chatMessage.text = connection.name + ": " + message;

						server.sendToAllTCP(chatMessage);
						return;
					}

				}

				public void disconnected(Connection c) {
					ChatConnection connection = (ChatConnection) c;
					if (connection.name != null) {
						// Announce to everyone that someone
						// (with a registered name) has left.
						ChatMessage chatMessage = new ChatMessage();
						chatMessage.text = connection.name + " disconnected.";
						server.sendToAllTCP(chatMessage);
						updateNames();
					}
				}
			});
			server.bind(Network.port);
			server.start();
		}

		public void stopServer() {
			server.stop();
			server.close();
		}

		void updateNames() {
			// Collect the names for each connection.
			Connection[] connections = server.getConnections();
			ArrayList names = new ArrayList(connections.length);
			for (int i = connections.length - 1; i >= 0; i--) {
				ChatConnection connection = (ChatConnection) connections[i];
				names.add(connection.name);
			}
			// Send the names to everyone.
			UpdateNames updateNames = new UpdateNames();
			updateNames.names = (String[]) names.toArray(new String[names
					.size()]);
			server.sendToAllTCP(updateNames);
		}
	}

	// This holds per connection state.
	public static class ChatConnection extends Connection {
		public String name;
	}

	public class ChatClient {

		public ProgressBar getPB() {
			return chatFrame.getPB();
		}

		public ChatClient() {
			client = new Client();
			client.start();

			Network.register(client);

			client.addListener(new Listener() {
				public void connected(Connection connection) {
					RegisterName registerName = new RegisterName();
					registerName.name = name;
					client.sendTCP(registerName);
				}

				public void received(Connection connection, Object object) {
					if (object instanceof UpdateNames) {
						UpdateNames updateNames = (UpdateNames) object;
						chatFrame.setNames(updateNames.names);
						return;
					}

					if (object instanceof ChatMessage) {
						ChatMessage chatMessage = (ChatMessage) object;
						chatFrame.addMessage(chatMessage.text);
						return;
					}
				}

				public void disconnected(Connection connection) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							// chatFrame = null;
						}
					});
				}
			});

			chatFrame = new ChatFrame(
					((RelativeLayout) findViewById(R.id.rlAll)).getContext());

			Runnable r = new Runnable() {
				public void run() {
					ChatMessage chatMessage = new ChatMessage();
					chatMessage.text = chatFrame.getSendText();
					client.sendTCP(chatMessage);
				}
			};
			chatFrame.setSendListener(r);
			chatFrame.setEditorActionListener(r);
			chatFrame.setKeyListener(r);
		}

		public void buildHost() {
			chatFrame.df.builderHostDialog();
		}

		public void buildUser() {
			chatFrame.df.builderUsernameDialog();
		}

		public void setErrorToastListener(final String fail) {
			chatFrame.setErrorToastListener(fail);
		}

		public class ChatFrame {

			public DialogFrame df;

			public ChatFrame(Context context) {
				this.context = context;
				initialize();
			}

			Context context;
			Button bSend;
			EditText etMessage;
			TextView tvChat, tvUsers, tvConnection;
			ScrollView svChatRecord;
			ProgressBar pbMain;

			public ProgressBar getPB() {
				return pbMain;
			}

			public void scrollToBottomChat() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						svChatRecord.post(new Runnable() {
							@Override
							public void run() {
								svChatRecord.fullScroll(View.FOCUS_DOWN);
								etMessage.requestFocus();
							}
						});
					}
				});
			}

			public void runConnect() {
				new ConnectToServer().execute();
			}

			public void sendMessage(final Runnable listener) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (getSendText().length() == 0) {
							return;
						}
						new RunListener(listener).execute();
						// listener.run();
					}
				});
			}

			private void initialize() {
				bSend = (Button) findViewById(R.id.bSend);

				etMessage = (EditText) findViewById(R.id.etMessage);

				tvChat = (TextView) findViewById(R.id.tvChat);
				tvUsers = (TextView) findViewById(R.id.tvUsers);
				tvConnection = (TextView) findViewById(R.id.tvConnection);

				svChatRecord = (ScrollView) findViewById(R.id.svChatRecord);

				pbMain = (ProgressBar) findViewById(R.id.pbMain);
				pbMain.setIndeterminate(true);
				pbMain.setVisibility(View.INVISIBLE);

				df = new DialogFrame();
			}

			public void setEditorActionListener(final Runnable listener) {
				etMessage
						.setOnEditorActionListener(new OnEditorActionListener() {
							@Override
							public boolean onEditorAction(TextView v,
									int actionId, KeyEvent event) {
								if (actionId == EditorInfo.IME_ACTION_SEND) {
									sendMessage(listener);
									return true;
								}
								return false;
							}
						});
			}

			public void setKeyListener(final Runnable listener) {
				etMessage.setOnKeyListener(new OnKeyListener() {
					@Override
					public boolean onKey(View v, int keyCode, KeyEvent event) {
						if ((event.getAction() == KeyEvent.ACTION_DOWN)
								&& (keyCode == KeyEvent.KEYCODE_ENTER)
								&& (!event.isShiftPressed())) {
							sendMessage(listener);
							return true;
						}
						return false;
					}
				});
			}

			public void setSendListener(final Runnable listener) {
				bSend.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						switch (v.getId()) {
						case R.id.bSend:
						default:
							sendMessage(listener);
							break;
						}
					}
				});
			}

			public void setOnPauseListener(final Runnable listener) {
				new RunListener(listener).execute();
			}

			public void setOnStopListener(final Runnable listener) {
				new RunListener(listener).execute();
			}

			public void onResume() {
				new ConnectToServer().execute();
			}

			public void setErrorToastListener(final String fail) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						Toast.makeText(ChatClientAndServer.this, fail,
								Toast.LENGTH_SHORT).show();
					}
				});
			}

			public String getSendText() {
				Editable e = etMessage.getText();
				String s = e.toString();
				s = s.trim();
				return s;
			}

			public void clearEditText() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						etMessage.setText("");
						scrollToBottomChat();
					}
				});
			}

			public void setNames(final String[] userNames) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i < userNames.length; i++) {
							sb.append(userNames[i] + "\n");
						}
						tvUsers.setText(sb.toString());
					}
				});
			}

			public void addMessage(final String chatMessage) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						String allChat = tvChat.getText().toString();
						allChat += "\n" + chatMessage;
						tvChat.setText(allChat);
						scrollToBottomChat();
					}
				});
			}

			public void clearChatHistory() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						String connected = "You are connected! " + host + ":"
								+ Network.port;
						tvChat.setText("\n" + connected + "\n");
						scrollToBottomChat();
					}
				});
			}

			// for android dialog boxes I use this subclass to manage them uniformly
			public class DialogFrame {

				// response to UserName dialog "ok" button click
				DialogInterface.OnClickListener dialogNOCLok = new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						processNameInput();
						connect();
					}
				};

				// response to Host dialog "ok" button click
				DialogInterface.OnClickListener dialogHOCLok = new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						processHostInput();
						clearChatHistory();
						connect();
					}
				};

				// response to Host and UserName dialog
				// "cancel" button click since it does the same thing
				DialogInterface.OnClickListener dialogOCLcancel = new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				};

				EditText etHost, etUserName;

				public void builderHostDialog() {
					// Request the host from the user.
					AlertDialog.Builder builderHost = new AlertDialog.Builder(
							context);
					etHost = new EditText(context);
					etHost.setOnClickListener(ChatClientAndServer.this);
					etHost.requestFocus();
					builderHost.setMessage("Enter Host (blank = 'localhost')")
							.setTitle("Connect to Chat Server")
							.setPositiveButton("Ok", dialogHOCLok)
							.setNegativeButton("Cancel", dialogOCLcancel)
							.setView(etHost);
					dialogHost = builderHost.create();
				}

				private void processHostInput() {
					String inputHo = "";

					inputHo = etHost.getText().toString();
					if (inputHo == null || inputHo.trim().length() == 0) {
						// if they entered nothing, assume localhost
						host = "localhost";
					} else {
						host = inputHo.trim();
					}

					SharedPreferences.Editor editor = savedHost.edit();
					editor.putString("Host", host);
					editor.commit();
				}

				public void builderUsernameDialog() {
					// Request the user's name.
					AlertDialog.Builder builderName = new AlertDialog.Builder(
							context);
					etUserName = new EditText(context);
					etUserName.setOnClickListener(ChatClientAndServer.this);
					etUserName.requestFocus();
					builderName.setMessage("Enter Your Screen Name")
							.setTitle("Log In...")
							.setPositiveButton("Ok", dialogNOCLok)
							.setNegativeButton("Cancel", dialogOCLcancel)
							.setView(etUserName);
					dialogName = builderName.create();
				}

				private void processNameInput() {
					String inputUN = "";

					inputUN = etUserName.getText().toString();
					if (inputUN == null || inputUN.trim().length() == 0) {
						name = "user" + new Random().nextInt(1000);
					} else {
						name = inputUN.trim();
					}

					SharedPreferences.Editor editor = savedName.edit();
					editor.putString("UserName", name);
					editor.commit();
				}
			}

			// this AsyncTask allows Server Connections in a separate thread
			// while still allowing uniformity no matter where it is called
			// in the activity
			public class ConnectToServer extends
					AsyncTask<String, int[], String> {

				@Override
				protected String doInBackground(String... params) {
					try {
						client.connect(5000, host, Network.port);
						// Server communication after connection can go
						// here, or in listener#connected().
					} catch (IOException ex) {
						ex.printStackTrace();
						chatFrame.setErrorToastListener("Failed to connect to server " 
						+ host + ":" + Network.port + "\n"
						+ "Please try another server.");
					}
					return null;
				}

				@Override
				protected void onPreExecute() {
					super.onPreExecute();
					pbMain.setVisibility(View.VISIBLE);
				}

				@Override
				protected void onPostExecute(String result) {
					super.onPostExecute(result);
					pbMain.setVisibility(View.INVISIBLE);
					tvConnection.setText(host + ":" + Network.port);
				}
			}
		}

		// unlike the java "new Thread" process, I opted for the AsyncTask class 
		// from Android 
		public class RunListener extends AsyncTask<String, int[], String> {
			Runnable listener;

			public RunListener(Runnable listener) {
				this.listener = listener;
			}

			@Override
			protected String doInBackground(String... params) {
				listener.run();
				return null;
			}

			@Override
			protected void onPostExecute(String result) {
				super.onPostExecute(result);
				chatFrame.clearEditText();
			}
		}
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		switch (item.getItemId()) {
		case R.id.i_exit:
			finish();
			break;
		case R.id.i_host:
			client.stop();
			cc.buildHost();
			dialogHost.show();
			break;
		case R.id.i_name:
			client.stop();
			cc.buildUser();
			dialogName.show();
			break;
		}
		return super.onMenuItemSelected(featureId, item);
	}

	// when an edittext needs a onclick method, but doesn't need to respond
	// in any special way, I used this one.
	@Override
	public void onClick(View v) {

	}

}