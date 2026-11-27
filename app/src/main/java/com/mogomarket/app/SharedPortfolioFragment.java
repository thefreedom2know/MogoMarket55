package com.mogomarket.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, live view of a portfolio another user shared with this user.
 * Listens straight to the owner's open positions, closed trades and cash, so buys, edits and
 * sells appear as soon as the owner makes them.
 */
public class SharedPortfolioFragment extends Fragment implements StocksAdapter.OnStockClickListener {

    static final String ARG_OWNER_UID = "ownerUid";
    static final String ARG_OWNER_EMAIL = "ownerEmail";

    private final List<StockData> openStocks = new ArrayList<>();
    private final List<StockData> closedTrades = new ArrayList<>();
    private StocksAdapter openAdapter;
    private ClosedTradesAdapter closedAdapter;
    private RecyclerView recyclerView;
    private TextView tvOwner, tvValue, tvPnl, tvClosedPnl, tvLastActivity, tvEmpty, tvLive;
    private com.google.android.material.button.MaterialButton btnOpen, btnClosed;
    private boolean showingClosed;
    private double cashBalance;

    private DatabaseReference ownerRef;
    private ValueEventListener openListener, closedListener, cashListener;
    private String ownerLabel = "Owner";
    /** Closed trade ids already seen, so a new sell can be announced. Null until first load. */
    @Nullable private Set<String> knownClosedIds;
    private boolean accessLost;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_shared_portfolio, container, false);
        tvOwner = view.findViewById(R.id.tvSharedOwner);
        tvLive = view.findViewById(R.id.tvSharedLive);
        tvValue = view.findViewById(R.id.tvSharedValue);
        tvPnl = view.findViewById(R.id.tvSharedPnl);
        tvClosedPnl = view.findViewById(R.id.tvSharedClosedPnl);
        tvLastActivity = view.findViewById(R.id.tvSharedLastActivity);
        tvEmpty = view.findViewById(R.id.tvSharedEmpty);
        recyclerView = view.findViewById(R.id.sharedRecyclerView);
        btnOpen = view.findViewById(R.id.btnSharedOpen);
        btnClosed = view.findViewById(R.id.btnSharedClosed);

        Bundle args = getArguments();
        String ownerUid = args == null ? null : args.getString(ARG_OWNER_UID);
        String ownerEmail = args == null ? null : args.getString(ARG_OWNER_EMAIL);
        if (ownerEmail != null && !ownerEmail.trim().isEmpty()) ownerLabel = ownerEmail.trim();
        tvOwner.setText(ownerLabel + "'s portfolio");

        openAdapter = new StocksAdapter(openStocks, this);
        openAdapter.setReadOnly(true);
        openAdapter.setPortfolioSummaryListener(this::showOpenSummary);
        closedAdapter = new ClosedTradesAdapter(closedTrades, null);
        closedAdapter.setReadOnly(true);
        closedAdapter.setSummaryListener((totalPnl, wins, total, invested, analytics) ->
                showClosedSummary(totalPnl, wins, total));

        MaterialButtonToggleGroup tabs = view.findViewById(R.id.sharedTabs);
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            showingClosed = checkedId == R.id.btnSharedClosed;
            bindList();
        });
        bindList();

        if (ownerUid == null || ownerUid.isEmpty()) {
            tvEmpty.setText("This shared portfolio could not be opened.");
            return view;
        }
        ownerRef = FirebaseDatabase.getInstance().getReference("users").child(ownerUid);
        listen();
        return view;
    }

    private void bindList() {
        if (recyclerView == null) return;
        if (showingClosed) {
            recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            recyclerView.setAdapter(closedAdapter);
        } else {
            recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
            recyclerView.setAdapter(openAdapter);
        }
        updateEmptyState();
    }

    private void listen() {
        openListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                openStocks.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    StockData stock = child.getValue(StockData.class);
                    if (stock != null) {
                        stock.firebaseKey = child.getKey();
                        openStocks.add(stock);
                    }
                }
                openAdapter.notifyDataSetChanged();
                openAdapter.updatePortfolioSummary();
                updateTabs();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onAccessError(error);
            }
        };
        ownerRef.child("portfolio-stocks").addValueEventListener(openListener);

        closedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<StockData> loaded = new ArrayList<>();
                Map<String, Integer> tradeNumbers = new HashMap<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    StockData trade = child.getValue(StockData.class);
                    if (trade == null) continue;
                    trade.closedTradeId = child.getKey();
                    String symbolKey = trade.symbol == null ? "" : trade.symbol.toUpperCase(Locale.US);
                    int number = tradeNumbers.getOrDefault(symbolKey, 0) + 1;
                    tradeNumbers.put(symbolKey, number);
                    if (trade.tradeNumber == null || trade.tradeNumber.trim().isEmpty()) {
                        trade.tradeNumber = StockData.toRomanNumeral(number);
                    }
                    loaded.add(trade);
                }
                announceNewSells(loaded);
                // Newest sells first, so the viewer sees what just happened.
                Collections.sort(loaded, (a, b) -> Long.compare(b.closedAt, a.closedAt));
                closedTrades.clear();
                closedTrades.addAll(loaded);
                closedAdapter.notifyDataSetChanged();
                closedAdapter.triggerSummaryUpdate();
                updateTabs();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onAccessError(error);
            }
        };
        ownerRef.child("closed-trades").addValueEventListener(closedListener);

        cashListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Double value = snapshot.getValue(Double.class);
                cashBalance = value != null ? value : 0.0;
                openAdapter.setCashBalance(cashBalance);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Cash is optional for the viewer; positions still show without it.
            }
        };
        ownerRef.child("portfolio-cash").addValueEventListener(cashListener);
    }

    /** Tells the viewer when the owner closes a position while this screen is open. */
    private void announceNewSells(List<StockData> loaded) {
        Set<String> ids = new HashSet<>();
        StockData newest = null;
        int newCount = 0;
        for (StockData trade : loaded) {
            if (trade.closedTradeId == null) continue;
            ids.add(trade.closedTradeId);
            if (knownClosedIds != null && !knownClosedIds.contains(trade.closedTradeId)) {
                newCount++;
                if (newest == null || trade.closedAt > newest.closedAt) newest = trade;
            }
        }
        boolean firstLoad = knownClosedIds == null;
        knownClosedIds = ids;
        if (firstLoad) {
            StockData latest = null;
            for (StockData trade : loaded) {
                if (latest == null || trade.closedAt > latest.closedAt) latest = trade;
            }
            if (latest != null) showLastActivity(latest, false);
            return;
        }
        if (newest == null) return;
        showLastActivity(newest, true);
        if (getContext() != null) {
            String message = newCount == 1
                    ? ownerLabel + " sold " + displaySymbol(newest)
                    : ownerLabel + " closed " + newCount + " trades";
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        }
    }

    private void showLastActivity(StockData trade, boolean justNow) {
        if (tvLastActivity == null) return;
        double pnl = StockData.calculateProfitLoss(trade);
        String when = justNow ? "Just now" : trade.closedAt > 0
                ? android.text.format.DateFormat.getDateFormat(requireContext())
                        .format(new java.util.Date(trade.closedAt)) : "Last sell";
        tvLastActivity.setText(String.format(Locale.US, "%s: sold %s at $%.2f (%s$%,.2f)",
                when, displaySymbol(trade), trade.sellPrice, pnl >= 0 ? "+" : "-", Math.abs(pnl)));
        tvLastActivity.setVisibility(View.VISIBLE);
    }

    private static String displaySymbol(StockData trade) {
        String symbol = trade.symbol == null ? "a position" : trade.symbol.trim();
        return CryptoHelper.isCryptoSymbol(symbol) ? CryptoHelper.getShortName(symbol) : symbol;
    }

    private void onAccessError(@NonNull DatabaseError error) {
        if (accessLost || !isAdded() || getContext() == null) return;
        accessLost = true;
        if (tvLive != null) {
            tvLive.setText("● OFFLINE");
            tvLive.setTextColor(requireContext().getColor(R.color.pf_ff7279));
        }
        String message = error.getCode() == DatabaseError.PERMISSION_DENIED
                ? ownerLabel + " is no longer sharing this portfolio with you."
                : "Couldn't load the shared portfolio: " + error.getMessage();
        tvEmpty.setText(message);
        tvEmpty.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    private void showOpenSummary(double invested, double marketValue, double totalPnl,
                                 double dailyPnl, double cash, int positionCount) {
        if (getView() == null) return;
        getView().post(() -> {
            if (tvValue == null) return;
            tvValue.setText(String.format(Locale.US, "$%,.2f", marketValue + cash));
            double pct = invested > 0 ? totalPnl / invested * 100.0 : 0;
            tvPnl.setText(String.format(Locale.US, "Open P&L %s$%,.2f (%+.2f%%) · Today %s$%,.2f",
                    totalPnl >= 0 ? "+" : "-", Math.abs(totalPnl), pct,
                    dailyPnl >= 0 ? "+" : "-", Math.abs(dailyPnl)));
            tvPnl.setTextColor(requireContext().getColor(totalPnl >= 0 ? R.color.gain : R.color.loss));
        });
    }

    private void showClosedSummary(double totalPnl, int wins, int total) {
        if (tvClosedPnl == null) return;
        int winRate = total > 0 ? (int) Math.round(wins * 100.0 / total) : 0;
        tvClosedPnl.setText(String.format(Locale.US, "Closed trades P&L %s$%,.2f · Win rate %d%%",
                totalPnl >= 0 ? "+" : "-", Math.abs(totalPnl), winRate));
    }

    private void updateTabs() {
        if (btnOpen == null) return;
        btnOpen.setText("Open (" + openStocks.size() + ")");
        btnClosed.setText("Closed (" + closedTrades.size() + ")");
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (tvEmpty == null || accessLost) return;
        boolean empty = showingClosed ? closedTrades.isEmpty() : openStocks.isEmpty();
        tvEmpty.setText(showingClosed ? "No closed trades yet." : "No open positions right now.");
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onStockClick(String symbol) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showChartWithSymbol(symbol);
        }
    }

    @Override
    public boolean supportsTradeDetails() {
        // Shows the sector line; edit, close and screenshots stay hidden in read-only mode.
        return true;
    }

    @Override
    public void onStockDelete(String symbol, double sellPrice, String exitReason,
                              double exitFees, double taxes) {
        // Read-only: the viewer can't close the owner's positions.
    }

    @Override
    public void onStockEdit(StockData updatedStock, String oldSymbol) {
        // Read-only.
    }

    @Override
    public void onManageScreenshots(StockData stock) {
        // Read-only.
    }

    @Override
    public void onDestroyView() {
        if (ownerRef != null) {
            if (openListener != null) ownerRef.child("portfolio-stocks").removeEventListener(openListener);
            if (closedListener != null) ownerRef.child("closed-trades").removeEventListener(closedListener);
            if (cashListener != null) ownerRef.child("portfolio-cash").removeEventListener(cashListener);
        }
        openListener = closedListener = cashListener = null;
        super.onDestroyView();
    }

    /** Opens the live view for one owner. */
    static void open(@NonNull Fragment from, @NonNull String ownerUid, @Nullable String ownerEmail) {
        Bundle args = new Bundle();
        args.putString(ARG_OWNER_UID, ownerUid);
        args.putString(ARG_OWNER_EMAIL, ownerEmail);
        NavHostFragment.findNavController(from).navigate(R.id.sharedPortfolioFragment, args);
    }
}
