package net.typeblog.shelter.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import net.typeblog.shelter.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String SETTINGS_VERSION = "settings_version";
    private static final String SETTINGS_SOURCE_CODE = "settings_source_code";

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(androidx.preference.R.id.recycler_view), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPaddingRelative(0, 0, 0, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        addPreferencesFromResource(R.xml.preferences_settings);
        findPreference("pocket_help").setOnPreferenceClickListener(pref -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(R.string.pocket_help)
                    .setMessage(R.string.pocket_help_text).setPositiveButton(android.R.string.ok,null).show();
            return true;
        });
        findPreference("pocket_licenses").setOnPreferenceClickListener(pref -> {
            try(java.io.InputStream input=requireContext().getAssets().open("THIRD_PARTY_NOTICES.txt")) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                android.widget.TextView text=new android.widget.TextView(requireContext());
                int padding=(int)(16*getResources().getDisplayMetrics().density);
                text.setPadding(padding,padding,padding,padding);text.setTextIsSelectable(true);
                text.setText(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                android.widget.ScrollView scroll=new android.widget.ScrollView(requireContext());scroll.addView(text);
                new androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(R.string.pocket_licenses)
                        .setView(scroll).setPositiveButton(android.R.string.ok,null).show();
            }catch(java.io.IOException e){android.widget.Toast.makeText(requireContext(),R.string.pocket_licenses_error,android.widget.Toast.LENGTH_LONG).show();}
            return true;
        });

        // Set the displayed version
        try {
            findPreference(SETTINGS_VERSION).setSummary(
                    getContext().getPackageManager().getPackageInfo(
                            getContext().getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            // WTF?
        }

        // Open source code url on click
        findPreference(SETTINGS_SOURCE_CODE)
                .setOnPreferenceClickListener(this::openSummaryUrl);
    }

    private boolean openSummaryUrl(Preference pref) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(pref.getSummary().toString()));
        startActivity(intent);
        return true;
    }

}
