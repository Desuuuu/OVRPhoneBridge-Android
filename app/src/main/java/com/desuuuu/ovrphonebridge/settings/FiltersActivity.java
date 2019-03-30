package com.desuuuu.ovrphonebridge.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import com.desuuuu.ovrphonebridge.Constants;
import com.desuuuu.ovrphonebridge.R;

import java.util.Objects;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class FiltersActivity extends AppCompatActivity {
    private SharedPreferences mSharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_filters);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCES_NAME, Context.MODE_PRIVATE);

        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_settings_filters, new FiltersFragment())
                .commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_settings_filters, menu);

        boolean state = mSharedPreferences.getBoolean("applications_show_system", false);

        MenuItem showSystemApplications = Objects.requireNonNull(menu.findItem(R.id.applications_show_system));

        showSystemApplications.setChecked(state);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.applications_show_system) {
            boolean newState = !item.isChecked();

            item.setChecked(newState);

            mSharedPreferences.edit().putBoolean("applications_show_system", newState).apply();

            return true;
        } else if (id == R.id.applications_reset) {
            mSharedPreferences.edit().remove("notifications_excluded_applications").commit();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
