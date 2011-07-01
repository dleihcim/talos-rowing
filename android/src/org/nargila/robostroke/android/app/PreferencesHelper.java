/*
 * Copyright (c) 2011 Tal Shalif
 * 
 * This file is part of Talos-Rowing.
 * 
 * Talos-Rowing is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Talos-Rowing is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Talos-Rowing.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * Copyright (c) 2011 Tal Shalif
 * 
 * This file is part of Talos-Rowing.
 * 
 * Talos-Rowing is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Talos-Rowing is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Talos-Rowing.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.nargila.robostroke.android.app;

import java.util.UUID;

import org.nargila.robostroke.param.Parameter;
import org.nargila.robostroke.param.ParameterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class PreferencesHelper {
	private static final int PREFERENCES_VERSION = 1;

	private static final String PREFERENCES_VERSION_RESET_KEY = "preferencesVersionReset" + PREFERENCES_VERSION;


	private static final String PREFERENCE_KEY_HRM_ENABLE = "org.nargila.talos.rowing.android.hrm.enable";

	private static final String PREFERENCE_KEY_PREFERENCES_RESET = "org.nargila.talos.rowing.android.preferences.reset";

	private final Logger logger = LoggerFactory.getLogger(getClass().getName());

	private final SharedPreferences preferences;
	
	private final SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
		
		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
				String key) {
			setParameterFromPreferences(key); 
			
			if (key.equals(PREFERENCE_KEY_HRM_ENABLE)) {
				owner.setEnableHrm(preferences.getBoolean(PREFERENCE_KEY_HRM_ENABLE, false), true);
			} else if (key.equals(PREFERENCE_KEY_PREFERENCES_RESET)) {
				preferences.edit().putBoolean(PREFERENCES_VERSION_RESET_KEY, true).commit();
				owner.graphPanelDisplayManager.resetNextRun();
			}
		}
	};

	private final RoboStrokeActivity owner;
	
	private final String uuid;
	
	public PreferencesHelper(RoboStrokeActivity owner) {
		this.owner = owner;
		
		preferences = PreferenceManager.getDefaultSharedPreferences(owner);
		
		{ // create UUID, if no exist
			String tmpUuid = preferences.getString("uuid", null);
			uuid =  tmpUuid == null ? UUID.randomUUID().toString() : tmpUuid;
		}
		
		resetPreferencesIfNeeded();
		
		initializePrefs();
		
		syncParametersFromPreferences();
		applyPreferences();
		attachPreferencesListener();
	}

	private void resetPreferencesIfNeeded() {

		boolean resetPending = !preferences.getAll().isEmpty() && preferences.getBoolean(PREFERENCES_VERSION_RESET_KEY, true);
		

		if (resetPending) {
			
			preferences.edit().putBoolean(PREFERENCE_KEY_PREFERENCES_RESET, false).commit();
			
			new AlertDialog.Builder(owner)
			.setMessage(R.string.preference_reset_dialog_message)
            .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                		preferences.edit().clear().putBoolean(PREFERENCES_VERSION_RESET_KEY, false).commit();
                   }
                })
            .setNeutralButton(R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                		preferences.edit().clear().putBoolean(PREFERENCES_VERSION_RESET_KEY, false).commit();                		 
                    }
                })
			.show();
		} 
		
		
	}

	public String getUUID() {
		return uuid;
	}
	
	private void initializePrefs() {
						
		if (preferences.getString("uuid", null) == null) {
			preferences.edit().putString("uuid", uuid).commit();
		}		
	}

	private void applyPreferences() {
		owner.setEnableHrm(preferences.getBoolean(PREFERENCE_KEY_HRM_ENABLE, false), false);
	}

	private void syncParametersFromPreferences() {
		logger.info("synchronizing Android preferences to back-end");
		
		for (String key: preferences.getAll().keySet()) {
			setParameterFromPreferences(key); 
		}
	}
	
	private void attachPreferencesListener() {
		
		preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
	}
	
	private void setParameterFromPreferences(String key) {
		if (key.startsWith("org.nargila.talos.rowing") && !key.startsWith("org.nargila.talos.rowing.android")) {
			logger.info("setting back-end parameter {} >>", key);

			final ParameterService ps = owner.getRoboStroke().getParameters();


			try {
				
				Parameter<?> param = ps.getParam(key);
				Object defaultValue = param.getDefaultValue();
				Object val = preferences.getAll().get(key);
				String value = val == null ? defaultValue.toString() : val.toString(); //preferences.edit().remove(key).commit()
				
				ps.setParam(key, value);

				logger.info("done setting back-end parameter {} with value {} <<", key, value);
				
			} catch (Exception e) {
				logger.error("error while trying to set back-end parameter from an Android preference <<", e);
			}
		}
	}

}
