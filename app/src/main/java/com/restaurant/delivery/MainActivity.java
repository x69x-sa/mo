package com.restaurant.delivery;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FusedLocationProviderClient locationClient;
    private ListenerRegistration ordersListener;
    private LinearLayout root;
    private static final String ORDER_CHANNEL_ID = "new_orders";
    private final Set<String> seenDriverOrders = new HashSet<>();
    private boolean driverSnapshotInitialized = false;

    private EditText phoneInput;
    private EditText nameInput;
    private EditText addressInput;
    private EditText latitudeInput;
    private EditText longitudeInput;
    private EditText notesInput;
    private EditText amountInput;
    private EditText orderNotesInput;
    private EditText paymentInput;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (fine || coarse) readCurrentLocation();
                else toast("يجب السماح بالوصول للموقع");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        showLoginOrRoute();
    }

    @Override
    protected void onDestroy() {
        stopOrdersListener();
        super.onDestroy();
    }

    private void showLoginOrRoute() {
        if (auth.getCurrentUser() == null) showLogin();
        else routeCurrentUser();
    }

    private void routeCurrentUser() {
        String uid = auth.getCurrentUser().getUid();
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    String role = doc.getString("role");
                    if ("driver".equalsIgnoreCase(role)) showDriverScreen();
                    else showAdminScreen();
                })
                .addOnFailureListener(err -> showAdminScreen());
    }

    private void baseScreen(String title) {
        stopOrdersListener();
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(24);
        heading.setTextColor(Color.rgb(17, 24, 39));
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 0, 0, dp(14));
        root.addView(heading);
    }

    private void showLogin() {
        baseScreen("إدارة توصيل المطعم");
        addText("تسجيل دخول المدير أو السائق", 16, Gravity.CENTER);

        EditText email = addField("البريد الإلكتروني");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = addField("كلمة المرور");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button login = makePrimaryButton("تسجيل الدخول");
        addFullButton(login);
        login.setOnClickListener(v -> {
            String e = email.getText().toString().trim();
            String p = password.getText().toString();
            if (e.isEmpty() || p.isEmpty()) {
                toast("أدخل البريد وكلمة المرور");
                return;
            }
            login.setEnabled(false);
            auth.signInWithEmailAndPassword(e, p)
                    .addOnSuccessListener(result -> routeCurrentUser())
                    .addOnFailureListener(err -> {
                        login.setEnabled(true);
                        toast("تعذر تسجيل الدخول: " + err.getLocalizedMessage());
                    });
        });
    }

    private void showAdminScreen() {
        baseScreen("لوحة المطعم");
        addText("متصل: " + safeEmail(), 13, Gravity.START);

        LinearLayout top = horizontal();
        Button driverView = makeSecondaryButton("واجهة السائق للتجربة");
        Button logout = makeSecondaryButton("تسجيل الخروج");
        top.addView(driverView, weighted());
        top.addView(logout, weighted());
        root.addView(top);
        driverView.setOnClickListener(v -> showDriverScreen());
        logout.setOnClickListener(v -> { auth.signOut(); showLogin(); });

        addSectionTitle("بحث العميل");
        phoneInput = addField("رقم الجوال 05xxxxxxxx");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);

        LinearLayout searchActions = horizontal();
        Button search = makePrimaryButton("بحث");
        Button clear = makeSecondaryButton("عميل جديد");
        searchActions.addView(search, weighted());
        searchActions.addView(clear, weighted());
        root.addView(searchActions);

        nameInput = addField("اسم العميل");
        addressInput = addField("العنوان التفصيلي");
        latitudeInput = addField("خط العرض");
        latitudeInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        longitudeInput = addField("خط الطول");
        longitudeInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        notesInput = addField("ملاحظات العنوان");

        LinearLayout locationRow = horizontal();
        Button location = makeSecondaryButton("التقاط الموقع");
        Button openMap = makeSecondaryButton("فتح الموقع");
        locationRow.addView(location, weighted());
        locationRow.addView(openMap, weighted());
        root.addView(locationRow);

        Button save = makePrimaryButton("حفظ العميل على الإنترنت");
        addFullButton(save);

        addSectionTitle("إنشاء طلب توصيل");
        amountInput = addField("قيمة الطلب بالريال");
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        paymentInput = addField("طريقة الدفع: نقدي أو شبكة");
        paymentInput.setText("نقدي");
        orderNotesInput = addField("تفاصيل الطلب أو ملاحظاته");

        Button createOrder = makeSuccessButton("إرسال الطلب إلى السائق");
        addFullButton(createOrder);

        LinearLayout bottom = horizontal();
        Button activeOrders = makeSecondaryButton("الطلبات النشطة");
        Button call = makeSecondaryButton("اتصال بالعميل");
        bottom.addView(activeOrders, weighted());
        bottom.addView(call, weighted());
        root.addView(bottom);

        Button completedOrders = makeSecondaryButton("سجل الطلبات المسلّمة");
        addFullButton(completedOrders);

        search.setOnClickListener(v -> searchCustomer());
        clear.setOnClickListener(v -> clearForm());
        location.setOnClickListener(v -> requestLocation());
        openMap.setOnClickListener(v -> openMapFromFields());
        save.setOnClickListener(v -> saveCustomer());
        createOrder.setOnClickListener(v -> createOrder());
        activeOrders.setOnClickListener(v -> showAdminOrders());
        completedOrders.setOnClickListener(v -> showCompletedOrders());
        call.setOnClickListener(v -> callCustomer());
    }

    private void showAdminOrders() {
        baseScreen("الطلبات النشطة");
        Button back = makeSecondaryButton("العودة للوحة المطعم");
        addFullButton(back);
        back.setOnClickListener(v -> showAdminScreen());

        TextView loading = addText("جاري تحميل الطلبات...", 15, Gravity.CENTER);
        ordersListener = db.collection("orders")
                .whereIn("status", Arrays.asList("NEW", "ACCEPTED", "ON_ROUTE"))
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        loading.setText("تعذر تحميل الطلبات: " + error.getLocalizedMessage());
                        return;
                    }
                    loading.setVisibility(View.GONE);
                    removeTaggedViews("order_card");
                    if (snapshots == null || snapshots.isEmpty()) {
                        TextView empty = orderMessage("لا توجد طلبات نشطة");
                        empty.setTag("order_card");
                        root.addView(empty);
                        return;
                    }
                    for (QueryDocumentSnapshot doc : snapshots) {
                        LinearLayout card = buildOrderCard(doc, false);
                        card.setTag("order_card");
                        root.addView(card);
                    }
                });
    }

    private void showCompletedOrders() {
        baseScreen("سجل الطلبات المسلّمة");
        Button back = makeSecondaryButton("العودة للوحة المطعم");
        addFullButton(back);
        back.setOnClickListener(v -> showAdminScreen());

        TextView loading = addText("جاري تحميل السجل...", 15, Gravity.CENTER);
        ordersListener = db.collection("orders")
                .whereEqualTo("status", "DELIVERED")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        loading.setText("تعذر تحميل السجل: " + error.getLocalizedMessage());
                        return;
                    }
                    if (snapshots != null) {
                        if (driverSnapshotInitialized) {
                            for (DocumentChange change : snapshots.getDocumentChanges()) {
                                if (change.getType() == DocumentChange.Type.ADDED
                                        && "NEW".equals(change.getDocument().getString("status"))
                                        && !seenDriverOrders.contains(change.getDocument().getId())) {
                                    notifyNewOrder(change.getDocument());
                                }
                            }
                        }
                        for (DocumentSnapshot item : snapshots.getDocuments()) {
                            seenDriverOrders.add(item.getId());
                        }
                        driverSnapshotInitialized = true;
                    }
                    loading.setVisibility(View.GONE);
                    removeTaggedViews("order_card");
                    if (snapshots == null || snapshots.isEmpty()) {
                        TextView empty = orderMessage("لا توجد طلبات مسلّمة بعد");
                        empty.setTag("order_card");
                        root.addView(empty);
                        return;
                    }
                    for (QueryDocumentSnapshot doc : snapshots) {
                        LinearLayout card = buildOrderCard(doc, false);
                        card.setTag("order_card");
                        root.addView(card);
                    }
                });
    }

    private void showDriverScreen() {
        driverSnapshotInitialized = false;
        seenDriverOrders.clear();
        setDriverAvailability(true);
        baseScreen("طلبات السائق");
        addText("الطلبات الجديدة تظهر هنا لحظيًا", 14, Gravity.CENTER);

        LinearLayout top = horizontal();
        Button admin = makeSecondaryButton("واجهة المطعم");
        Button logout = makeSecondaryButton("تسجيل الخروج");
        top.addView(admin, weighted());
        top.addView(logout, weighted());
        root.addView(top);
        admin.setOnClickListener(v -> routeCurrentUser());
        logout.setOnClickListener(v -> { auth.signOut(); showLogin(); });

        TextView loading = addText("جاري الاتصال بقاعدة البيانات...", 15, Gravity.CENTER);
        ordersListener = db.collection("orders")
                .whereIn("status", Arrays.asList("NEW", "ACCEPTED", "ON_ROUTE"))
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        loading.setText("تعذر تحميل الطلبات: " + error.getLocalizedMessage());
                        return;
                    }
                    loading.setVisibility(View.GONE);
                    removeTaggedViews("order_card");
                    if (snapshots == null || snapshots.isEmpty()) {
                        TextView empty = orderMessage("لا توجد طلبات حالية");
                        empty.setTag("order_card");
                        root.addView(empty);
                        return;
                    }
                    for (QueryDocumentSnapshot doc : snapshots) {
                        LinearLayout card = buildOrderCard(doc, true);
                        card.setTag("order_card");
                        root.addView(card);
                    }
                });
    }

    private LinearLayout buildOrderCard(DocumentSnapshot doc, boolean driverMode) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(243, 244, 246));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(cardParams);

        String name = text(doc.getString("customerName"));
        String phone = text(doc.getString("customerPhone"));
        String address = text(doc.getString("address"));
        String notes = text(doc.getString("notes"));
        String payment = text(doc.getString("paymentMethod"));
        String status = text(doc.getString("status"));
        Double amount = doc.getDouble("amount");
        Double lat = doc.getDouble("latitude");
        Double lng = doc.getDouble("longitude");

        TextView title = plainText("طلب " + doc.getId() + " — " + statusArabic(status), 17, Gravity.START);
        title.setTextColor(Color.rgb(17, 24, 39));
        card.addView(title);
        card.addView(plainText(name + " | " + phone, 16, Gravity.START));
        card.addView(plainText(address, 14, Gravity.START));
        if (!notes.isEmpty()) card.addView(plainText("ملاحظات: " + notes, 14, Gravity.START));
        card.addView(plainText("القيمة: " + money(amount) + " ر.س — " + payment, 15, Gravity.START));

        LinearLayout actions = horizontal();
        Button map = makeSuccessButton("الملاحة");
        Button call = makeSecondaryButton("اتصال");
        actions.addView(map, weighted());
        actions.addView(call, weighted());
        card.addView(actions);
        map.setOnClickListener(v -> openNavigation(lat, lng));
        call.setOnClickListener(v -> dial(phone));

        if (driverMode) {
            if ("NEW".equals(status)) {
                Button accept = makePrimaryButton("استلام الطلب");
                card.addView(accept, fullButtonParams());
                accept.setOnClickListener(v -> updateOrderStatus(doc.getId(), "ACCEPTED"));
            }
            if ("ACCEPTED".equals(status) || "NEW".equals(status)) {
                Button route = makePrimaryButton("بدء التوصيل");
                card.addView(route, fullButtonParams());
                route.setOnClickListener(v -> updateOrderStatus(doc.getId(), "ON_ROUTE"));
            }
            Button delivered = makeSuccessButton("تم التسليم");
            card.addView(delivered, fullButtonParams());
            delivered.setOnClickListener(v -> updateOrderStatus(doc.getId(), "DELIVERED"));
        } else if (!"DELIVERED".equals(status) && !"CANCELLED".equals(status)) {
            Button cancel = makeDangerButton("إلغاء الطلب");
            card.addView(cancel, fullButtonParams());
            cancel.setOnClickListener(v -> updateOrderStatus(doc.getId(), "CANCELLED"));
        }
        return card;
    }

    private void createOrder() {
        String phone = normalizePhone(phoneInput.getText().toString());
        String name = nameInput.getText().toString().trim();
        Double amount = parseDouble(amountInput.getText().toString());
        Double lat = parseDouble(latitudeInput.getText().toString());
        Double lng = parseDouble(longitudeInput.getText().toString());
        if (phone.length() != 10 || !phone.startsWith("05")) {
            toast("ابحث عن العميل أو أدخل رقم جوال صحيح");
            return;
        }
        if (name.isEmpty()) { toast("بيانات العميل غير مكتملة"); return; }
        if (amount == null || amount < 0) { toast("أدخل قيمة الطلب"); return; }
        if (lat == null || lng == null) { toast("أدخل موقع العميل أو التقطه"); return; }

        Map<String, Object> order = new HashMap<>();
        order.put("customerPhone", phone);
        order.put("customerName", name);
        order.put("address", addressInput.getText().toString().trim());
        order.put("latitude", lat);
        order.put("longitude", lng);
        order.put("amount", amount);
        order.put("paymentMethod", paymentInput.getText().toString().trim().isEmpty() ? "نقدي" : paymentInput.getText().toString().trim());
        order.put("notes", orderNotesInput.getText().toString().trim());
        order.put("status", "NEW");
        order.put("branchId", "main");
        order.put("createdAt", FieldValue.serverTimestamp());
        order.put("updatedAt", FieldValue.serverTimestamp());
        order.put("createdBy", auth.getCurrentUser() == null ? "" : auth.getCurrentUser().getUid());

        db.collection("orders").add(order)
                .addOnSuccessListener(ref -> {
                    toast("تم إرسال الطلب إلى السائق");
                    amountInput.setText("");
                    orderNotesInput.setText("");
                })
                .addOnFailureListener(err -> toast("تعذر إنشاء الطلب: " + err.getLocalizedMessage()));
    }

    private void updateOrderStatus(String orderId, String status) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        update.put("updatedAt", FieldValue.serverTimestamp());
        if ("DELIVERED".equals(status)) update.put("deliveredAt", FieldValue.serverTimestamp());
        db.collection("orders").document(orderId).update(update)
                .addOnSuccessListener(v -> {
                    if ("ACCEPTED".equals(status) || "ON_ROUTE".equals(status)) setDriverAvailability(false);
                    if ("DELIVERED".equals(status) || "CANCELLED".equals(status)) setDriverAvailability(true);
                    toast("تم تحديث حالة الطلب");
                })
                .addOnFailureListener(err -> toast("تعذر تحديث الطلب: " + err.getLocalizedMessage()));
    }

    private void searchCustomer() {
        String phone = normalizePhone(phoneInput.getText().toString());
        if (phone.isEmpty()) { toast("أدخل رقم الجوال"); return; }
        phoneInput.setText(phone);
        db.collection("customers").document(phone).get()
                .addOnSuccessListener(this::fillCustomer)
                .addOnFailureListener(err -> toast("تعذر البحث: " + err.getLocalizedMessage()));
    }

    private void fillCustomer(DocumentSnapshot doc) {
        if (!doc.exists()) {
            clearCustomerFields();
            toast("العميل غير مسجل؛ أدخل بياناته ثم احفظ");
            return;
        }
        nameInput.setText(text(doc.getString("name")));
        addressInput.setText(text(doc.getString("address")));
        notesInput.setText(text(doc.getString("notes")));
        Double lat = doc.getDouble("latitude");
        Double lng = doc.getDouble("longitude");
        latitudeInput.setText(lat == null ? "" : String.valueOf(lat));
        longitudeInput.setText(lng == null ? "" : String.valueOf(lng));
        toast("تم تحميل بيانات العميل");
    }

    private void saveCustomer() {
        String phone = normalizePhone(phoneInput.getText().toString());
        String name = nameInput.getText().toString().trim();
        if (phone.length() != 10 || !phone.startsWith("05")) {
            toast("أدخل رقم جوال سعودي صحيح");
            return;
        }
        if (name.isEmpty()) { toast("أدخل اسم العميل"); return; }

        Map<String, Object> customer = new HashMap<>();
        customer.put("phone", phone);
        customer.put("name", name);
        customer.put("address", addressInput.getText().toString().trim());
        customer.put("notes", notesInput.getText().toString().trim());
        customer.put("latitude", parseDouble(latitudeInput.getText().toString()));
        customer.put("longitude", parseDouble(longitudeInput.getText().toString()));
        customer.put("updatedAt", FieldValue.serverTimestamp());

        db.collection("customers").document(phone)
                .set(customer, SetOptions.merge())
                .addOnSuccessListener(v -> toast("تم حفظ العميل بنجاح"))
                .addOnFailureListener(err -> toast("تعذر الحفظ: " + err.getLocalizedMessage()));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ORDER_CHANNEL_ID,
                    "الطلبات الجديدة",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("تنبيهات وصول طلبات توصيل جديدة");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2026);
        }
    }

    private void notifyNewOrder(DocumentSnapshot order) {
        String customer = text(order.getString("customerName"));
        String phone = text(order.getString("customerPhone"));
        Double amount = order.getDouble("amount");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ORDER_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("طلب توصيل جديد")
                .setContentText(customer + " — " + phone + " — " + money(amount) + " ر.س")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        try {
            if (Build.VERSION.SDK_INT < 33
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this).notify(order.getId().hashCode(), builder.build());
            }
        } catch (SecurityException ignored) {
        }
    }

    private void setDriverAvailability(boolean available) {
        if (auth.getCurrentUser() == null) return;
        Map<String, Object> driver = new HashMap<>();
        driver.put("available", available);
        driver.put("lastSeenAt", FieldValue.serverTimestamp());
        driver.put("role", "driver");
        db.collection("users").document(auth.getCurrentUser().getUid())
                .set(driver, SetOptions.merge());
    }

    private void requestLocation() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) readCurrentLocation();
        else locationPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void readCurrentLocation() {
        try {
            locationClient.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location == null) { toast("تعذر تحديد الموقع؛ تأكد من تشغيل GPS"); return; }
                        latitudeInput.setText(String.format(Locale.US, "%.6f", location.getLatitude()));
                        longitudeInput.setText(String.format(Locale.US, "%.6f", location.getLongitude()));
                        toast("تم التقاط الموقع");
                    })
                    .addOnFailureListener(err -> toast("خطأ في الموقع: " + err.getLocalizedMessage()));
        } catch (SecurityException ex) {
            toast("إذن الموقع غير متاح");
        }
    }

    private void openMapFromFields() {
        openNavigation(parseDouble(latitudeInput.getText().toString()), parseDouble(longitudeInput.getText().toString()));
    }

    private void openNavigation(Double lat, Double lng) {
        if (lat == null || lng == null) { toast("لا توجد إحداثيات صحيحة"); return; }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lng));
        intent.setPackage("com.google.android.apps.maps");
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + lat + "," + lng));
        }
        startActivity(intent);
    }

    private void callCustomer() {
        dial(normalizePhone(phoneInput.getText().toString()));
    }

    private void dial(String phone) {
        if (phone == null || phone.trim().isEmpty()) { toast("لا يوجد رقم جوال"); return; }
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
    }

    private String normalizePhone(String raw) {
        String p = raw == null ? "" : raw.replaceAll("\\D", "");
        if (p.startsWith("00966")) p = p.substring(5);
        else if (p.startsWith("966")) p = p.substring(3);
        if (p.length() == 9 && p.startsWith("5")) p = "0" + p;
        return p;
    }

    private void clearForm() {
        phoneInput.setText("");
        clearCustomerFields();
        amountInput.setText("");
        orderNotesInput.setText("");
        phoneInput.requestFocus();
    }

    private void clearCustomerFields() {
        nameInput.setText("");
        addressInput.setText("");
        latitudeInput.setText("");
        longitudeInput.setText("");
        notesInput.setText("");
    }

    private void stopOrdersListener() {
        if (ordersListener != null) {
            ordersListener.remove();
            ordersListener = null;
        }
    }

    private void removeTaggedViews(String tag) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (tag.equals(child.getTag())) root.removeViewAt(i);
        }
    }

    private EditText addField(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(16);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        root.addView(input, lp);
        return input;
    }

    private TextView addText(String value, int size, int gravity) {
        TextView t = plainText(value, size, gravity);
        root.addView(t);
        return t;
    }

    private TextView plainText(String value, int size, int gravity) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setGravity(gravity);
        t.setTextColor(Color.rgb(55, 65, 81));
        t.setPadding(dp(4), dp(5), dp(4), dp(5));
        return t;
    }

    private void addSectionTitle(String text) {
        TextView title = plainText(text, 19, Gravity.START);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setPadding(dp(4), dp(18), dp(4), dp(6));
        root.addView(title);
    }

    private TextView orderMessage(String text) {
        TextView t = plainText(text, 16, Gravity.CENTER);
        t.setPadding(dp(8), dp(30), dp(8), dp(30));
        return t;
    }

    private Button makePrimaryButton(String text) { return makeButton(text, Color.rgb(17, 24, 39), Color.WHITE); }
    private Button makeSecondaryButton(String text) { return makeButton(text, Color.rgb(229, 231, 235), Color.rgb(17, 24, 39)); }
    private Button makeSuccessButton(String text) { return makeButton(text, Color.rgb(4, 120, 87), Color.WHITE); }
    private Button makeDangerButton(String text) { return makeButton(text, Color.rgb(185, 28, 28), Color.WHITE); }

    private Button makeButton(String text, int background, int foreground) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(foreground);
        b.setBackgroundColor(background);
        b.setAllCaps(false);
        return b;
    }

    private void addFullButton(Button b) { root.addView(b, fullButtonParams()); }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        lp.setMargins(0, dp(7), 0, dp(7));
        return lp;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        return lp;
    }

    private String statusArabic(String status) {
        if ("NEW".equals(status)) return "جديد";
        if ("ACCEPTED".equals(status)) return "تم الاستلام";
        if ("ON_ROUTE".equals(status)) return "في الطريق";
        if ("DELIVERED".equals(status)) return "تم التسليم";
        if ("CANCELLED".equals(status)) return "ملغي";
        return status;
    }

    private String money(Double value) {
        if (value == null) return "0.00";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(value);
    }

    private String safeEmail() {
        return auth.getCurrentUser() == null || auth.getCurrentUser().getEmail() == null ? "" : auth.getCurrentUser().getEmail();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String text(String value) { return value == null ? "" : value; }
    private Double parseDouble(String value) {
        try { return value == null || value.trim().isEmpty() ? null : Double.parseDouble(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
