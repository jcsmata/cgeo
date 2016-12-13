package cgeo.geocaching.address;

import cgeo.geocaching.CacheListActivity;
import cgeo.geocaching.Intents;
import cgeo.geocaching.R;
import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.ui.recyclerview.RecyclerViewProvider;
import cgeo.geocaching.utils.AndroidRxUtils;

import android.app.ProgressDialog;
import android.location.Address;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import org.apache.commons.lang3.StringUtils;

public class AddressListActivity extends AbstractActionBarActivity implements AddressClickListener {

    @NonNull
    private final ArrayList<Address> addresses = new ArrayList<>();

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState, R.layout.addresslist_activity);

        final AddressListAdapter adapter = new AddressListAdapter(addresses, this);
        final RecyclerView view = RecyclerViewProvider.provideRecyclerView(this, R.id.address_list, false, true);
        view.setAdapter(adapter);

        final String keyword = getIntent().getStringExtra(Intents.EXTRA_KEYWORD);
        final ProgressDialog waitDialog =
                ProgressDialog.show(this, res.getString(R.string.search_address_started), keyword, true);
        waitDialog.setCancelable(true);
        lookupAddressInBackground(keyword, adapter, waitDialog);
    }

    private void lookupAddressInBackground(final String keyword, final AddressListAdapter adapter, final ProgressDialog waitDialog) {
        final Observable<Address> geocoderObservable = new AndroidGeocoder(this).getFromLocationName(keyword)
                .onErrorResumeNext(MapQuestGeocoder.getFromLocationName(keyword));
        AndroidRxUtils.bindActivity(this, geocoderObservable.toList()).subscribe(new Consumer<List<Address>>() {
            @Override
            public void accept(final List<Address> foundAddresses) {
                waitDialog.dismiss();
                addresses.addAll(foundAddresses);
                adapter.notifyItemRangeInserted(0, foundAddresses.size());
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(final Throwable throwable) {
                finish();
                showToast(res.getString(R.string.err_unknown_address));
            }
        });
    }

    @Override
    public void onClickAddress(final Address address) {
        CacheListActivity.startActivityAddress(this, new Geopoint(address.getLatitude(), address.getLongitude()), StringUtils.defaultString(address.getAddressLine(0)));
        finish();
    }

}