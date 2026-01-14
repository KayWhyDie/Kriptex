package com.ivor.kriptex;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.ivor.kriptex.db.Database;
import com.ivor.kriptex.utils.Settings;

//import com.ivor.kriptex.transformation.CircleTransform;
//import com.squareup.picasso.Picasso;

public class CreateNewActivity extends AppCompatActivity {

    private static final String TAG = "CreateNewActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_new);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        ((RadioGroup) findViewById(R.id.radioGroup)).setOnCheckedChangeListener((radioGroup, i) -> {

            switch (i) {
                case R.id.rdbtnLightTheme:
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    Settings.putBoolean(getApplicationContext(), "use_dark_mode", false);
//                    recreate();
                    break;
                case R.id.rdbtnDarkTheme:
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    Settings.putBoolean(getApplicationContext(), "use_dark_mode", true);
//                    recreate();
                    break;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    public void onStartClicked(View v) {

        EditText txtName = findViewById(R.id.txtName);
        if (txtName.length() < 1) {
            txtName.setError("Please enter name");
            return;
        }
        Database.getInstance(this).setName(txtName.getText().toString().trim());
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);

        Settings.putBoolean(getApplicationContext(), "start_setup_completed", true);
    }
}
