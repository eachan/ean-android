/*
 * Copyright (c) 2013 EAN.com, L.P. All rights reserved.
 */

package com.ean.mobile.activity;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.ean.mobile.Destination;
import com.ean.mobile.R;
import com.ean.mobile.SampleApp;
import com.ean.mobile.SampleConstants;
import com.ean.mobile.exception.EanWsError;
import com.ean.mobile.exception.UrlRedirectionException;
import com.ean.mobile.hotel.request.ListRequest;
import com.ean.mobile.request.RequestProcessor;
import com.ean.mobile.task.SuggestionFactory;

/**
 * The code behind the StartupSearch layout.
 */
public class StartupSearch extends Activity {

    private static final String DATE_FORMAT_STRING = "MM/dd/yyyy";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormat.forPattern(DATE_FORMAT_STRING);

    private static final long TEN_MEGABYTES = 10 * 1024 * 1024;

    /**
     * {@inheritDoc}
     */
    @Override
    public final void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.startupsearch);

        SampleApp.resetDates();
        setUpDateButton(R.id.arrival_date_picker, SampleApp.arrivalDate);
        setUpDateButton(R.id.departure_date_picker, SampleApp.departureDate);

        setUpPeopleSpinner(R.id.adults_spinner);
        setUpPeopleSpinner(R.id.children_spinner);

        final ArrayAdapter<Destination> suggestionAdapter
                = new DestinationSuggestionAdapter(getApplicationContext(), R.id.suggestionsView);
        final SearchBoxTextWatcher watcher = new SearchBoxTextWatcher(suggestionAdapter);

        final EditText searchBox = (EditText) findViewById(R.id.searchBox);
        searchBox.setOnKeyListener(new SearchBoxKeyListener(searchBox));
        searchBox.addTextChangedListener(watcher);

        final ListView suggestions = (ListView) findViewById(R.id.suggestionsView);
        suggestions.setAdapter(suggestionAdapter);
        suggestions.setOnItemClickListener(new SuggestionListAdapterClickListener(searchBox, watcher));

        setupHttpConnectionStuff();
    }

    private void setupHttpConnectionStuff() {
        // exists due to advice found at http://android-developers.blogspot.com/2011/09/androids-http-clients.html.
        // HTTP connection reuse which was buggy pre-froyo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
        try {
            final long httpCacheSize = TEN_MEGABYTES;
            final File httpCacheDir = new File(getCacheDir(), "http");
            Class.forName("android.net.http.HttpResponseCache")
                    .getMethod("install", File.class, long.class)
                    .invoke(null, httpCacheDir, httpCacheSize);
        } catch (ClassNotFoundException cnfe) {
            //do nothing, just swallow it.
        } catch (NoSuchMethodException nsme) {
            //do nothing, just swallow it.
        } catch (IllegalAccessException iae) {
            //do nothing, just swallow it.
        } catch (InvocationTargetException ite) {
            //do nothing, just swallow it.
        }
    }

    private void setUpDateButton(final int resourceId, final LocalDate date) {
        final Button dateButton = (Button) findViewById(resourceId);
        dateButton.setText(DATE_TIME_FORMATTER.print(date));
    }

    private void setUpPeopleSpinner(final int resourceId) {
        final ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(
            getApplicationContext(),
            R.array.number_of_people_array,
            android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        final Spinner spinner = (Spinner) findViewById(resourceId);
        spinner.setAdapter(spinnerAdapter);
    }

    /**
     * (Event handler) Handles the clicks on either of the date dialogs.
     * @param view The view (button) that fired the event.
     */
    public void showDatePickerDialog(final View view) {
        new DatePickerFragment(view.getId(), (Button) view).show(getFragmentManager(), "StartupSearchDatePicker");
    }

    private void performSearch(final String searchQuery, final ProgressDialog searchingDialog) {
        SuggestionFactory.killSuggestDestinationTask();
        SampleApp.clearSearch();

        final Button arrivalDatePicker = (Button) findViewById(R.id.arrival_date_picker);
        final Button departureDatePicker = (Button) findViewById(R.id.departure_date_picker);
        final Spinner adultsSpinner = (Spinner) findViewById(R.id.adults_spinner);
        final Spinner childSpinner = (Spinner) findViewById(R.id.adults_spinner);

        SampleApp.searchQuery = searchQuery;
        SampleApp.arrivalDate = DATE_TIME_FORMATTER.parseLocalDate(arrivalDatePicker.getText().toString());
        SampleApp.departureDate = DATE_TIME_FORMATTER.parseLocalDate(departureDatePicker.getText().toString());
        SampleApp.numberOfAdults = Integer.parseInt((String) adultsSpinner.getSelectedItem());
        SampleApp.numberOfChildren = Integer.parseInt((String) childSpinner.getSelectedItem());
        
        new PerformSearchTask(searchingDialog).execute((Void) null);
    }

    private final class PerformSearchTask extends AsyncTask<Void, Integer, Void> {

        private final ProgressDialog searchingDialog;

        private PerformSearchTask(final ProgressDialog searchingDialog) {
            this.searchingDialog = searchingDialog;
        }

        @Override
        protected Void doInBackground(final Void... voids) {
            try {
                final ListRequest request = new ListRequest(
                    SampleApp.searchQuery,
                    SampleApp.occupancy(),
                    SampleApp.arrivalDate,
                    SampleApp.departureDate);

                SampleApp.updateFoundHotels(RequestProcessor.run(request), true);
            } catch (EanWsError ewe) {
                //TODO: This should be handled better.
                // If this exception occurs, it's likely an input error and should be recoverable.
                Log.d(SampleConstants.LOG_TAG, "An APILevel Exception occurred.", ewe);
            } catch (UrlRedirectionException ure) {
                SampleApp.sendRedirectionToast(getApplicationContext());
            }
            return null;
         }

        @Override
        protected void onPostExecute(final Void aVoid) {
            startActivity(new Intent(StartupSearch.this, HotelList.class));
            try {
                searchingDialog.dismiss();
            } catch (IllegalArgumentException iae) {
                // just ignore it... it's because the window rotated at an inopportune time.
            }
        }

        @Override
        protected void onCancelled() {
            searchingDialog.dismiss();
        }
    }

    private static final class DatePickerFragment extends DialogFragment implements DatePickerDialog.OnDateSetListener {

        private final int pickerId;

        private final Button pickerButton;

        public DatePickerFragment(final int pickerId, final Button pickerButton) {
            super();
            this.pickerId = pickerId;
            this.pickerButton = pickerButton;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final LocalDate date;
            if (pickerId == R.id.arrival_date_picker) {
                date = SampleApp.arrivalDate;
            } else {
                date = SampleApp.departureDate;
            }

            return new DatePickerDialog(
                getActivity(),
                this,
                date.getYear(),
                date.getMonthOfYear() - 1,
                date.getDayOfMonth());
        }

        @Override
        public void onDateSet(final DatePicker datePicker, final int year, final int monthOfYear,
                final int dayOfMonth) {
            final LocalDate chosenDate = new LocalDate(year, monthOfYear + 1, dayOfMonth);
            pickerButton.setText(DATE_TIME_FORMATTER.print(chosenDate));
            switch(pickerId) {
                case R.id.arrival_date_picker:
                    SampleApp.arrivalDate = chosenDate;
                    break;
                case R.id.departure_date_picker:
                    SampleApp.departureDate = chosenDate;
                    break;
                default:
                    break;
            }
        }
    }

    private static final class SuggestionListAdapterClickListener implements AdapterView.OnItemClickListener {

        private final EditText searchBox;

        private final TextWatcher textWatcher;

        public SuggestionListAdapterClickListener(final EditText searchBox, final TextWatcher textWatcher) {
            this.searchBox = searchBox;
            this.textWatcher = textWatcher;
        }

        public void onItemClick(final AdapterView<?> parent, final View view, final int position, final long id) {
            //replace the text in the search box with the string at position.
            final Destination selectedDestination = (Destination) parent.getItemAtPosition(position);
            //disable the text change listener.
            searchBox.removeTextChangedListener(textWatcher);
            //set the text.
            searchBox.setText(selectedDestination.name);
            //clear the suggestions
            final ArrayAdapter suggestionAdapter = (ArrayAdapter) parent.getAdapter();
            suggestionAdapter.clear();
            suggestionAdapter.notifyDataSetChanged();
            //re-enable the change listener.
            searchBox.addTextChangedListener(textWatcher);
        }
    }

    private final class SearchBoxKeyListener implements View.OnKeyListener {
        private final EditText searchBox;

        public SearchBoxKeyListener(final EditText searchBox) {
            this.searchBox = searchBox;
        }

        public boolean onKey(final View v, final int keyCode, final KeyEvent event) {
            // Only hears hardware keys and enter per OnKeyListener's javadoc.
            // If the event is a key-down event on the "enter" button
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                // Perform action on key press
                performSearch(
                    searchBox.getText().toString().trim(),
                    ProgressDialog.show(StartupSearch.this, "", getString(R.string.searching), true));
                return true;
            }
            return false;
        }
    }

    private final class SearchBoxTextWatcher implements TextWatcher {

        private final ArrayAdapter<Destination> suggestionAdapter;

        private SearchBoxTextWatcher(final ArrayAdapter<Destination> suggestionAdapter) {
            this.suggestionAdapter = suggestionAdapter;
        }

        @Override
        public void beforeTextChanged(final CharSequence charSequence, final int i, final int i2, final int i3) { }

        @Override
        public void onTextChanged(final CharSequence charSequence, final int i, final int i2, final int i3) { }

        @Override
        public void afterTextChanged(final Editable editable) {
            SuggestionFactory.suggest(editable.toString(), suggestionAdapter);
        }
    }

    private static final class DestinationSuggestionAdapter extends ArrayAdapter<Destination> {
        private final LayoutInflater layoutInflater;

        private DestinationSuggestionAdapter(final Context context, final int resource) {
            super(context, resource, new ArrayList<Destination>());
            layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = layoutInflater.inflate(R.layout.destinationlistlayout, null);
            }

            final Destination destination = this.getItem(position);

            //Set the name field
            final TextView name = (TextView) view.findViewById(R.id.destinationSuggestionName);
            name.setText(destination.name);

            return view;
        }
    }
}

