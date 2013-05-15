package com.ameron32.chatclienttest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * @author klemeilleur
 * This is really just a splash screen. Doesn't have to be there.
 * R.layout.selector is just the layout for the splash screen, no more.
 */
public class MainActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.selector);
		run();
	}

	private void run() {
		Class activity;
		try {
			activity = Class.forName("com.ameron32.chatclienttest." + "ChatClientAndServer");
			Intent openActivity = new Intent(MainActivity.this, activity);
			startActivity(openActivity);
			finish();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

}

