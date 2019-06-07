package com.desuuuu.ovrphonebridge.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;

import com.desuuuu.ovrphonebridge.Constants;
import com.desuuuu.ovrphonebridge.R;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

public class FiltersFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, Preference.OnPreferenceChangeListener {
    private SharedPreferences mSharedPreferences;

    private boolean mShowSystemApps;
    private Set<String> mExcludedApplications;
    private PackageManager mPackageManager;
    private ListMultimap<String, ApplicationInfo> mApplicationsList;

    private Context mPreferenceContext;
    private PreferenceCategory mApplicationsCategory;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Objects.requireNonNull(getContext());

        getPreferenceManager().setSharedPreferencesName(Constants.PREFERENCES_NAME);
        getPreferenceManager().setSharedPreferencesMode(Context.MODE_PRIVATE);

        mSharedPreferences = getPreferenceManager().getSharedPreferences();

        mShowSystemApps = mSharedPreferences.getBoolean("applications_show_system", false);

        mExcludedApplications = Objects.requireNonNull(mSharedPreferences.getStringSet(
                "notifications_excluded_applications",
                new HashSet<>()));

        mPackageManager = getContext().getPackageManager();

        mApplicationsList = listApplications(getContext());

        mPreferenceContext = getPreferenceManager().getContext();

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(mPreferenceContext);

        mApplicationsCategory = new PreferenceCategory(mPreferenceContext);
        mApplicationsCategory.setKey("application_filters");
        mApplicationsCategory.setTitle(R.string.applications);

        preferenceScreen.addPreference(mApplicationsCategory);

        populateApplicationList();

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);

        setPreferenceScreen(preferenceScreen);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mSharedPreferences != null) {
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);

            mSharedPreferences = null;
        }

        if (mApplicationsCategory != null) {
            mApplicationsCategory.removeAll();

            mApplicationsCategory = null;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (mPreferenceContext == null || mApplicationsCategory == null) {
            return;
        }

        if (key.equals("notifications_excluded_applications")) {
            mExcludedApplications = Objects.requireNonNull(sharedPreferences.getStringSet(
                    "notifications_excluded_applications",
                    new HashSet<>()));

            populateApplicationList();
        } else if (key.equals("applications_show_system")) {
            mShowSystemApps = sharedPreferences.getBoolean("applications_show_system", false);

            populateApplicationList();
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String packageName = preference.getKey();

        if ((boolean)newValue) {
            mExcludedApplications.remove(packageName);
        } else {
            mExcludedApplications.add(packageName);
        }

        mSharedPreferences.edit().putStringSet("notifications_excluded_applications", mExcludedApplications).commit();

        return true;
    }

    private void populateApplicationList() {
        mApplicationsCategory.removeAll();

        boolean empty = true;

        for (Map.Entry<String, ApplicationInfo> entry : mApplicationsList.entries()) {
            String applicationName = entry.getKey();
            ApplicationInfo applicationInfo = entry.getValue();

            if (!mShowSystemApps && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            Drawable applicationLogo = mPackageManager.getApplicationIcon(applicationInfo);

            CheckBoxPreference preference = new CheckBoxPreference(mPreferenceContext);
            preference.setKey(applicationInfo.packageName);
            preference.setTitle(applicationName);
            preference.setIcon(applicationLogo);
            preference.setChecked(!mExcludedApplications.contains(applicationInfo.packageName));
            preference.setOnPreferenceChangeListener(this);
            preference.setPersistent(false);

            mApplicationsCategory.addPreference(preference);

            empty = false;
        }

        if (empty) {
            Preference emptyPreference = new Preference(mPreferenceContext);
            emptyPreference.setKey("application_filters_empty");
            emptyPreference.setTitle(R.string.applications_list_empty);
            emptyPreference.setEnabled(false);

            mApplicationsCategory.addPreference(emptyPreference);
        }
    }

    private ListMultimap<String, ApplicationInfo> listApplications(Context context) {
        ListMultimap<String, ApplicationInfo> result = MultimapBuilder.treeKeys(String.CASE_INSENSITIVE_ORDER).arrayListValues().build();

        List<ApplicationInfo> applicationInfoList = mPackageManager.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo applicationInfo : applicationInfoList) {
            if (applicationInfo.packageName.equals(context.getPackageName())) {
                continue;
            }

            CharSequence applicationName = mPackageManager.getApplicationLabel(applicationInfo);

            if (TextUtils.isEmpty(applicationName)
                    || applicationName.toString().startsWith(applicationInfo.packageName)) {
                continue;
            }

            result.put(applicationName.toString(), applicationInfo);
        }

        return result;
    }
}
