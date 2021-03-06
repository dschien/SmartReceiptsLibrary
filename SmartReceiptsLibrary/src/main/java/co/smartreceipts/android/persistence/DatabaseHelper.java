package co.smartreceipts.android.persistence;

import java.io.File;
import java.io.IOException;
import java.sql.Date;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import wb.android.autocomplete.AutoCompleteAdapter;
import wb.android.flex.Flex;
import wb.android.storage.StorageManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import co.smartreceipts.android.BuildConfig;
import co.smartreceipts.android.SmartReceiptsApplication;
import co.smartreceipts.android.date.DateUtils;
import co.smartreceipts.android.model.CSVColumns;
import co.smartreceipts.android.model.Columns.Column;
import co.smartreceipts.android.model.PDFColumns;
import co.smartreceipts.android.model.PaymentMethod;
import co.smartreceipts.android.model.ReceiptRow;
import co.smartreceipts.android.model.TripRow;
import co.smartreceipts.android.model.WBCurrency;
import co.smartreceipts.android.utils.Utils;
import co.smartreceipts.android.workers.ImportTask;

public final class DatabaseHelper extends SQLiteOpenHelper implements AutoCompleteAdapter.QueryListener, AutoCompleteAdapter.ItemSelectedListener {

	// Logging Vars
	private static final boolean D = true;
	private static final String TAG = "DatabaseHelper";

	// Database Info
	public static final String DATABASE_NAME = "receipts.db";
	private static final int DATABASE_VERSION = 12;
	public static final String NO_DATA = "null"; // TODO: Just set to null
	static final String MULTI_CURRENCY = "XXXXXX";

	// Tags
	public static final String TAG_TRIPS = "Trips";
	public static final String TAG_RECEIPTS_NAME = "Receipts";
	public static final String TAG_RECEIPTS_COMMENT = "Receipts_Comment";

	// InstanceVar
	private static DatabaseHelper INSTANCE = null;

	// Caching Vars
	private TripRow[] mTripsCache;
	private boolean mAreTripsValid;
	private final HashMap<TripRow, List<ReceiptRow>> mReceiptCache;
	private int mNextReceiptAutoIncrementId = -1;
	private HashMap<String, String> mCategories;
	private ArrayList<CharSequence> mCategoryList, mCurrencyList;
	private CSVColumns mCSVColumns;
	private PDFColumns mPDFColumns;
	private List<PaymentMethod> mPaymentMethods;
	private Time mNow;

	// Other vars
	private final Context mContext;
	private final Flex mFlex;
	private final PersistenceManager mPersistenceManager;
	private final TableDefaultsCustomizer mCustomizations;

	// Listeners
	private TripRowListener mTripRowListener;
	private ReceiptRowListener mReceiptRowListener;
	private ReceiptRowGraphListener mReceiptRowGraphListener;

	// Locks
	private final Object mDatabaseLock = new Object();
	private final Object mReceiptCacheLock = new Object();
	private final Object mTripCacheLock = new Object();

	// Misc Vars
	private boolean mIsDBOpen = false;

	// Hack to prevent Recursive Database Calling
	private SQLiteDatabase _initDB; // This is only set while either onCreate or onUpdate is running. It is null all
									// other times

	public interface TripRowListener {
		public void onTripRowsQuerySuccess(TripRow[] trips);

		public void onTripRowInsertSuccess(TripRow trip);

		public void onTripRowInsertFailure(SQLException ex, File directory); // Directory here is out of mDate

		public void onTripRowUpdateSuccess(TripRow trip);

		public void onTripRowUpdateFailure(TripRow newTrip, TripRow oldTrip, File directory); // For rollback info

		public void onTripDeleteSuccess(TripRow oldTrip);

		public void onTripDeleteFailure();

		public void onSQLCorruptionException();
	}

	public interface ReceiptRowListener {
		public void onReceiptRowsQuerySuccess(List<ReceiptRow> receipts);

		public void onReceiptRowInsertSuccess(ReceiptRow receipt);

		public void onReceiptRowInsertFailure(SQLException ex); // Directory here is out of mDate

		public void onReceiptRowUpdateSuccess(ReceiptRow receipt);

		public void onReceiptRowUpdateFailure(); // For rollback info

		public void onReceiptDeleteSuccess(ReceiptRow receipt);

		public void onReceiptRowAutoCompleteQueryResult(String name, String price, String category); // Any of these can
																										// be null!

		public void onReceiptCopySuccess(TripRow tripRow);

		public void onReceiptCopyFailure();

		public void onReceiptMoveSuccess(TripRow tripRow);

		public void onReceiptMoveFailure();

		public void onReceiptDeleteFailure();
	}

	public interface ReceiptRowGraphListener {
		public void onGraphQuerySuccess(List<ReceiptRow> receipts);
	}

	public interface TableDefaultsCustomizer {
		public void onFirstRun();

		public void insertCategoryDefaults(DatabaseHelper db);

		public void insertCSVDefaults(DatabaseHelper db);

		public void insertPDFDefaults(DatabaseHelper db);

		public void insertPaymentMethodDefaults(DatabaseHelper db);
	}

	// Tables Declarations
	// Remember to update the merge() command below when adding columns
	private static final class TripsTable {
		private TripsTable() {
		}

		public static final String TABLE_NAME = "trips";
		public static final String COLUMN_NAME = "name";
		public static final String COLUMN_FROM = "from_date";
		public static final String COLUMN_TO = "to_date";
		public static final String COLUMN_FROM_TIMEZONE = "from_timezone";
		public static final String COLUMN_TO_TIMEZONE = "to_timezone";
		@SuppressWarnings("unused")
		@Deprecated
		public static final String COLUMN_PRICE = "price"; // Deprecated, since this is receipt info
		public static final String COLUMN_MILEAGE = "miles_new";
		public static final String COLUMN_COMMENT = "trips_comment";
		public static final String COLUMN_DEFAULT_CURRENCY = "trips_default_currency";
		public static final String COLUMN_FILTERS = "trips_filters";
	}

	private static final class ReceiptsTable {
		private ReceiptsTable() {
		}

		public static final String TABLE_NAME = "receipts";
		public static final String COLUMN_ID = "id";
		public static final String COLUMN_PATH = "path";
		public static final String COLUMN_NAME = "name";
		public static final String COLUMN_PARENT = "parent";
		public static final String COLUMN_CATEGORY = "category";
		public static final String COLUMN_PRICE = "price";
		public static final String COLUMN_TAX = "tax";
		public static final String COLUMN_DATE = "rcpt_date";
		public static final String COLUMN_TIMEZONE = "timezone";
		public static final String COLUMN_COMMENT = "comment";
		public static final String COLUMN_EXPENSEABLE = "expenseable";
		public static final String COLUMN_ISO4217 = "isocode";
		public static final String COLUMN_PAYMENT_METHOD_ID = "paymentMethodKey";
		public static final String COLUMN_NOTFULLPAGEIMAGE = "fullpageimage";
		public static final String COLUMN_EXTRA_EDITTEXT_1 = "extra_edittext_1";
		public static final String COLUMN_EXTRA_EDITTEXT_2 = "extra_edittext_2";
		public static final String COLUMN_EXTRA_EDITTEXT_3 = "extra_edittext_3";
	}

	private static final class CategoriesTable {
		private CategoriesTable() {
		}

		public static final String TABLE_NAME = "categories";
		public static final String COLUMN_NAME = "name";
		public static final String COLUMN_CODE = "code";
		public static final String COLUMN_BREAKDOWN = "breakdown";
	}

	public static final class CSVTable {
		private CSVTable() {
		}

		public static final String TABLE_NAME = "csvcolumns";
		public static final String COLUMN_ID = "id";
		public static final String COLUMN_TYPE = "type";
	}

	public static final class PDFTable {
		private PDFTable() {
		}

		public static final String TABLE_NAME = "pdfcolumns";
		public static final String COLUMN_ID = "id";
		public static final String COLUMN_TYPE = "type";
	}

	public static final class PaymentMethodsTable {
		private PaymentMethodsTable() {
		}

		public static final String TABLE_NAME = "paymentmethods";
		public static final String COLUMN_ID = "id";
		public static final String COLUMN_METHOD = "method";
	}

	private DatabaseHelper(SmartReceiptsApplication application, PersistenceManager persistenceManager, String databasePath) {
		super(application.getApplicationContext(), databasePath, null, DATABASE_VERSION); // Requests the default cursor
																							// factory
		mAreTripsValid = false;
		mReceiptCache = new HashMap<TripRow, List<ReceiptRow>>();
		mContext = application.getApplicationContext();
		mFlex = application.getFlex();
		mPersistenceManager = persistenceManager;
		mCustomizations = application;
		this.getReadableDatabase(); // Called here, so onCreate gets called on the UI thread
	}

	public static final DatabaseHelper getInstance(SmartReceiptsApplication application, PersistenceManager persistenceManager) {
		if (INSTANCE == null || !INSTANCE.isOpen()) { // If we don't have an instance or it's closed
			String databasePath = StorageManager.GetRootPath();
			if (BuildConfig.DEBUG) {
				if (databasePath.equals("")) {
					throw new RuntimeException("The SDCard must be created beforoe GetRootPath is called in DBHelper");
				}
			}
			if (!databasePath.endsWith(File.separator)) {
				databasePath = databasePath + File.separator;
			}
			databasePath = databasePath + DATABASE_NAME;
			INSTANCE = new DatabaseHelper(application, persistenceManager, databasePath);
		}
		return INSTANCE;
	}

	public static final DatabaseHelper getNewInstance(SmartReceiptsApplication application, PersistenceManager persistenceManager) {
		String databasePath = StorageManager.GetRootPath();
		if (BuildConfig.DEBUG) {
			if (databasePath.equals("")) {
				throw new RuntimeException("The SDCard must be created beforoe GetRootPath is called in DBHelper");
			}
		}
		if (!databasePath.endsWith(File.separator)) {
			databasePath = databasePath + File.separator;
		}
		databasePath = databasePath + DATABASE_NAME;
		return new DatabaseHelper(application, persistenceManager, databasePath);
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// Begin Abstract Method Overrides
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public void onCreate(final SQLiteDatabase db) {
		synchronized (mDatabaseLock) {
			_initDB = db;
			// N.B. This only gets called if you actually request the database using the getDatabase method
			final String trips = "CREATE TABLE " + TripsTable.TABLE_NAME + " (" + TripsTable.COLUMN_NAME + " TEXT PRIMARY KEY, " + TripsTable.COLUMN_FROM + " DATE, " + TripsTable.COLUMN_TO + " DATE, " + TripsTable.COLUMN_FROM_TIMEZONE + " TEXT, " + TripsTable.COLUMN_TO_TIMEZONE + " TEXT, "
			/* + TripsTable.COLUMN_PRICE + " DECIMAL(10, 2) DEFAULT 0.00, " */
			+ TripsTable.COLUMN_MILEAGE + " DECIMAL(10, 2) DEFAULT 0.00, " + TripsTable.COLUMN_COMMENT + " TEXT, " + TripsTable.COLUMN_DEFAULT_CURRENCY + " TEXT, " + TripsTable.COLUMN_FILTERS + " TEXT" + ");";
			final String receipts = "CREATE TABLE " + ReceiptsTable.TABLE_NAME + " (" + ReceiptsTable.COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ReceiptsTable.COLUMN_PATH + " TEXT, " + ReceiptsTable.COLUMN_PARENT + " TEXT REFERENCES " + TripsTable.TABLE_NAME + " ON DELETE CASCADE, " + ReceiptsTable.COLUMN_NAME + " TEXT DEFAULT \"New Receipt\", " + ReceiptsTable.COLUMN_CATEGORY + " TEXT, " + ReceiptsTable.COLUMN_DATE + " DATE DEFAULT (DATE('now', 'localtime')), " + ReceiptsTable.COLUMN_TIMEZONE + " TEXT, " + ReceiptsTable.COLUMN_COMMENT + " TEXT, " + ReceiptsTable.COLUMN_ISO4217 + " TEXT NOT NULL, " + ReceiptsTable.COLUMN_PRICE + " DECIMAL(10, 2) DEFAULT 0.00, " + ReceiptsTable.COLUMN_TAX + " DECIMAL(10, 2) DEFAULT 0.00, " + ReceiptsTable.COLUMN_PAYMENT_METHOD_ID + " INTEGER REFERENCES " + PaymentMethodsTable.TABLE_NAME + " ON DELETE NO ACTION, " + ReceiptsTable.COLUMN_EXPENSEABLE + " BOOLEAN DEFAULT 1, " + ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE + " BOOLEAN DEFAULT 1, " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1 + " TEXT, " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2 + " TEXT, " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3 + " TEXT" + ");";
			final String categories = "CREATE TABLE " + CategoriesTable.TABLE_NAME + " (" + CategoriesTable.COLUMN_NAME + " TEXT PRIMARY KEY, " + CategoriesTable.COLUMN_CODE + " TEXT, " + CategoriesTable.COLUMN_BREAKDOWN + " BOOLEAN DEFAULT 1" + ");";
			if (BuildConfig.DEBUG) {
				Log.d(TAG, trips);
			}
			if (BuildConfig.DEBUG) {
				Log.d(TAG, receipts);
			}
			if (BuildConfig.DEBUG) {
				Log.d(TAG, categories);
			}
			db.execSQL(trips);
			db.execSQL(receipts);
			db.execSQL(categories);
			this.createCSVTable(db);
			this.createPDFTable(db);
			this.createPaymentMethodsTable(db);
			mCustomizations.insertCategoryDefaults(this);
			mCustomizations.onFirstRun();
			_initDB = null;
		}
	}

	@Override
	public final void onUpgrade(final SQLiteDatabase db, int oldVersion, final int newVersion) {
		synchronized (mDatabaseLock) {

			if (D) {
				Log.d(TAG, "Upgrading the database from version " + oldVersion + " to " + newVersion);
			}

			// Try to backup the database to the SD Card for support reasons
			final StorageManager storageManager = mPersistenceManager.getStorageManager();
			File sdDB = storageManager.getFile(DATABASE_NAME + "." + oldVersion + ".bak");
			try {
				storageManager.copy(new File(db.getPath()), sdDB, true);
				if (D) {
					Log.d(TAG, "Backed up database file to: " + sdDB.getName());
				}
			}
			catch (IOException e) {
				Log.e(TAG, "Failed to back up database: " + e.toString());
			}

			_initDB = db;
			if (oldVersion <= 1) { // Add mCurrency column to receipts table
				final String alterReceipts = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_ISO4217 + " TEXT NOT NULL " + "DEFAULT " + mPersistenceManager.getPreferences().getDefaultCurreny();
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterReceipts);
				}
				db.execSQL(alterReceipts);
			}
			if (oldVersion <= 2) { // Add the mileage field to trips, add the breakdown boolean to categories, and
									// create the CSV table
				final String alterCategories = "ALTER TABLE " + CategoriesTable.TABLE_NAME + " ADD " + CategoriesTable.COLUMN_BREAKDOWN + " BOOLEAN DEFAULT 1";
				if (D) {
					Log.d(TAG, alterCategories);
				}
				db.execSQL(alterCategories);
				this.createCSVTable(db);
			}
			if (oldVersion <= 3) { // Add extra_edittext columns
				final String alterReceipts1 = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1 + " TEXT";
				final String alterReceipts2 = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2 + " TEXT";
				final String alterReceipts3 = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3 + " TEXT";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterReceipts1);
				}
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterReceipts2);
				}
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterReceipts3);
				}
				db.execSQL(alterReceipts1);
				db.execSQL(alterReceipts2);
				db.execSQL(alterReceipts3);
			}
			if (oldVersion <= 4) { // Change Mileage to Decimal instead of Integer
				final String alterMiles = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_MILEAGE + " DECIMAL(10, 2) DEFAULT 0.00";
				final String alterReceipts1 = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_TAX + " DECIMAL(10, 2) DEFAULT 0.00";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterMiles);
					Log.d(TAG, alterReceipts1);
				}
			}
			if (oldVersion <= 5) {
				// Skipped b/c I forgot to include the update stuff
			}
			if (oldVersion <= 6) { // Fix the database to replace absolute paths with relative ones
				final Cursor tripsCursor = db.query(TripsTable.TABLE_NAME, new String[] { TripsTable.COLUMN_NAME }, null, null, null, null, null);
				if (tripsCursor != null && tripsCursor.moveToFirst()) {
					final int nameIndex = tripsCursor.getColumnIndex(TripsTable.COLUMN_NAME);
					do {
						String absPath = tripsCursor.getString(nameIndex);
						if (absPath.endsWith(File.separator)) {
							absPath = absPath.substring(0, absPath.length() - 1);
						}
						final String relPath = absPath.substring(absPath.lastIndexOf(File.separatorChar) + 1, absPath.length());
						if (BuildConfig.DEBUG) {
							Log.d(TAG, "Updating Abs. Trip Path: " + absPath + " => " + relPath);
						}
						final ContentValues tripValues = new ContentValues(1);
						tripValues.put(TripsTable.COLUMN_NAME, relPath);
						if (db.update(TripsTable.TABLE_NAME, tripValues, TripsTable.COLUMN_NAME + " = ?", new String[] { absPath }) == 0) {
							if (BuildConfig.DEBUG) {
								Log.e(TAG, "Trip Update Error Occured");
							}
						}
					}
					while (tripsCursor.moveToNext());
				}
				// TODO: Finally clause here
				tripsCursor.close();

				final Cursor receiptsCursor = db.query(ReceiptsTable.TABLE_NAME, new String[] { ReceiptsTable.COLUMN_ID, ReceiptsTable.COLUMN_PARENT, ReceiptsTable.COLUMN_PATH }, null, null, null, null, null);
				if (receiptsCursor != null && receiptsCursor.moveToFirst()) {
					final int idIdx = receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_ID);
					final int parentIdx = receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_PARENT);
					final int imgIdx = receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_PATH);
					do {
						final int id = receiptsCursor.getInt(idIdx);
						String absParentPath = receiptsCursor.getString(parentIdx);
						if (absParentPath.endsWith(File.separator)) {
							absParentPath = absParentPath.substring(0, absParentPath.length() - 1);
						}
						final String absImgPath = receiptsCursor.getString(imgIdx);
						final ContentValues receiptValues = new ContentValues(2);
						final String relParentPath = absParentPath.substring(absParentPath.lastIndexOf(File.separatorChar) + 1, absParentPath.length());
						receiptValues.put(ReceiptsTable.COLUMN_PARENT, relParentPath);
						if (BuildConfig.DEBUG) {
							Log.d(TAG, "Updating Abs. Parent Path for Receipt" + id + ": " + absParentPath + " => " + relParentPath);
						}
						;
						if (!absImgPath.equalsIgnoreCase(NO_DATA)) { // This can be either a path or NO_DATA
							final String relImgPath = absImgPath.substring(absImgPath.lastIndexOf(File.separatorChar) + 1, absImgPath.length());
							receiptValues.put(ReceiptsTable.COLUMN_PATH, relImgPath);
							if (BuildConfig.DEBUG) {
								Log.d(TAG, "Updating Abs. Img Path for Receipt" + id + ": " + absImgPath + " => " + relImgPath);
							}
						}
						if (db.update(ReceiptsTable.TABLE_NAME, receiptValues, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(id) }) == 0) {
							if (BuildConfig.DEBUG) {
								Log.e(TAG, "Receipt Update Error Occured");
							}
						}
					}
					while (receiptsCursor.moveToNext());
				}
				receiptsCursor.close();
			}
			if (oldVersion <= 7) { // Added a timezone column to the receipts table
				final String alterReceipts = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_TIMEZONE + " TEXT";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterReceipts);
				}
				db.execSQL(alterReceipts);
			}
			if (oldVersion <= 8) { // Added a timezone column to the trips table
				final String alterTrips1 = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_FROM_TIMEZONE + " TEXT";
				final String alterTrips2 = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_TO_TIMEZONE + " TEXT";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterTrips1);
				}
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterTrips2);
				}
				db.execSQL(alterTrips1);
				db.execSQL(alterTrips2);
			}
			if (oldVersion <= 9) { // Added a PDF table
				this.createPDFTable(db);
			}
			if (oldVersion <= 10) {
				final String alterTrips1 = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_COMMENT + " TEXT";
				final String alterTrips2 = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_DEFAULT_CURRENCY + " TEXT";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterTrips1);
				}
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterTrips2);
				}
				db.execSQL(alterTrips1);
				db.execSQL(alterTrips2);
			}
			if (oldVersion <= 11) { // Added trips filters, payment methods, and mileage table
				this.createPaymentMethodsTable(db);
				final String alterTrips = "ALTER TABLE " + TripsTable.TABLE_NAME + " ADD " + TripsTable.COLUMN_FILTERS + " TEXT";
				final String alterReceipts = "ALTER TABLE " + ReceiptsTable.TABLE_NAME + " ADD " + ReceiptsTable.COLUMN_PAYMENT_METHOD_ID + " INTEGER REFERENCES " + PaymentMethodsTable.TABLE_NAME + " ON DELETE NO ACTION";
				if (BuildConfig.DEBUG) {
					Log.d(TAG, alterTrips);
					Log.d(TAG, alterReceipts);
				}
				db.execSQL(alterTrips);
				db.execSQL(alterReceipts);
			}
			_initDB = null;
		}
	}

	@Override
	public void onOpen(SQLiteDatabase db) {
		super.onOpen(db);
		mIsDBOpen = true;
	}

	@Override
	public synchronized void close() {
		super.close();
		mIsDBOpen = false;
	}

	public boolean isOpen() {
		return mIsDBOpen;
	}

	public void onDestroy() {
		try {
			this.close();
		}
		catch (Exception e) {
			// This can be called from finalize, so operate cautiously
			Log.e(TAG, e.toString());
		}
	}

	@Override
	protected void finalize() throws Throwable {
		onDestroy(); // Close our resources if we still need
		super.finalize();
	}

	/*
	 * public final void testPrintDBValues() { final SQLiteDatabase db = this.getReadableDatabase(); final Cursor
	 * tripsCursor = db.query(TripsTable.TABLE_NAME, new String[] {TripsTable.COLUMN_NAME}, null, null, null, null,
	 * null); String data = ""; if (BuildConfig.DEBUG) Log.d(TAG,
	 * "=================== Printing Trips ==================="); if (BuildConfig.DEBUG) data +=
	 * "=================== Printing Trips ===================" + "\n"; if (tripsCursor != null &&
	 * tripsCursor.moveToFirst()) { final int nameIndex = tripsCursor.getColumnIndex(TripsTable.COLUMN_NAME); do { if
	 * (BuildConfig.DEBUG) Log.d(TAG, tripsCursor.getString(nameIndex)); if (BuildConfig.DEBUG) data += "\"" +
	 * tripsCursor.getString(nameIndex) + "\"";
	 * 
	 * } while (tripsCursor.moveToNext()); }
	 * 
	 * final Cursor receiptsCursor = db.query(ReceiptsTable.TABLE_NAME, new String[] {ReceiptsTable.COLUMN_ID,
	 * ReceiptsTable.COLUMN_PARENT, ReceiptsTable.COLUMN_PATH}, null, null, null, null, null); if (BuildConfig.DEBUG)
	 * Log.d(TAG, "=================== Printing Receipts ==================="); if (BuildConfig.DEBUG) data +=
	 * "=================== Printing Receipts ===================" + "\n"; if (receiptsCursor != null &&
	 * receiptsCursor.moveToFirst()) { final int idIdx = receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_ID); final
	 * int parentIdx = receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_PARENT); final int imgIdx =
	 * receiptsCursor.getColumnIndex(ReceiptsTable.COLUMN_PATH); do { if (BuildConfig.DEBUG) Log.d(TAG, "(" +
	 * receiptsCursor.getInt(idIdx) + ", " + receiptsCursor.getString(parentIdx) + ", " +
	 * receiptsCursor.getString(imgIdx) + ")"); if (BuildConfig.DEBUG) data += "(" + receiptsCursor.getInt(idIdx) + ", "
	 * + receiptsCursor.getString(parentIdx) + ", " + receiptsCursor.getString(imgIdx) + ")" + "\n"; } while
	 * (receiptsCursor.moveToNext()); } mContext.getStorageManager().write("db.txt", data); }
	 */

	private final void createCSVTable(final SQLiteDatabase db) { // Called in onCreate and onUpgrade
		final String csv = "CREATE TABLE " + CSVTable.TABLE_NAME + " (" + CSVTable.COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + CSVTable.COLUMN_TYPE + " TEXT" + ");";
		if (BuildConfig.DEBUG) {
			Log.d(TAG, csv);
		}
		db.execSQL(csv);
		mCustomizations.insertCSVDefaults(this);
	}

	private final void createPDFTable(final SQLiteDatabase db) { // Called in onCreate and onUpgrade
		final String pdf = "CREATE TABLE " + PDFTable.TABLE_NAME + " (" + PDFTable.COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + PDFTable.COLUMN_TYPE + " TEXT" + ");";
		if (BuildConfig.DEBUG) {
			Log.d(TAG, pdf);
		}
		db.execSQL(pdf);
		mCustomizations.insertPDFDefaults(this);
	}

	private final void createPaymentMethodsTable(final SQLiteDatabase db) { // Called in onCreate and onUpgrade
		final String sql = "CREATE TABLE " + PaymentMethodsTable.TABLE_NAME + " (" + PDFTable.COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " + PaymentMethodsTable.COLUMN_METHOD + " TEXT" + ");";
		if (BuildConfig.DEBUG) {
			Log.d(TAG, sql);
		}
		db.execSQL(sql);
		mCustomizations.insertPaymentMethodDefaults(this);
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// TripRow Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public void registerTripRowListener(TripRowListener listener) {
		mTripRowListener = listener;
	}

	public void unregisterTripRowListener(TripRowListener listener) {
		mTripRowListener = null;
	}

	public TripRow[] getTripsSerial() throws SQLiteDatabaseCorruptException {
		synchronized (mTripCacheLock) {
			if (mAreTripsValid) {
				return mTripsCache;
			}
		}
		TripRow[] trips = getTripsHelper();
		synchronized (mTripCacheLock) {
			if (!mAreTripsValid) {
				mAreTripsValid = true;
				mTripsCache = trips;
			}
			return mTripsCache;
		}
	}

	public void getTripsParallel() {
		if (mTripRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No TripRowListener was registered.");
			}
		}
		else {
			synchronized (mTripCacheLock) {
				if (mAreTripsValid) {
					mTripRowListener.onTripRowsQuerySuccess(mTripsCache);
					return;
				}
			}
			(new GetTripsWorker()).execute(new Void[0]);
		}
	}

	private static final String CURR_CNT_QUERY = "SELECT COUNT(*), " + ReceiptsTable.COLUMN_ISO4217 + " FROM (SELECT COUNT(*), " + ReceiptsTable.COLUMN_ISO4217 + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_PARENT + "=? GROUP BY " + ReceiptsTable.COLUMN_ISO4217 + ");";

	private TripRow[] getTripsHelper() throws SQLiteDatabaseCorruptException {
		SQLiteDatabase db = null;
		Cursor c = null, qc = null;
		synchronized (mDatabaseLock) {
			TripRow[] trips;
			try {
				db = this.getReadableDatabase();
				c = db.query(TripsTable.TABLE_NAME, null, null, null, null, null, TripsTable.COLUMN_TO + " DESC");
				if (c != null && c.moveToFirst()) {
					trips = new TripRow[c.getCount()];
					final int nameIndex = c.getColumnIndex(TripsTable.COLUMN_NAME);
					final int fromIndex = c.getColumnIndex(TripsTable.COLUMN_FROM);
					final int toIndex = c.getColumnIndex(TripsTable.COLUMN_TO);
					final int fromTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_FROM_TIMEZONE);
					final int toTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_TO_TIMEZONE);
					// final int priceIndex = c.getColumnIndex(TripsTable.COLUMN_PRICE);
					final int milesIndex = c.getColumnIndex(TripsTable.COLUMN_MILEAGE);
					final int commentIndex = c.getColumnIndex(TripsTable.COLUMN_COMMENT);
					final int defaultCurrencyIndex = c.getColumnIndex(TripsTable.COLUMN_DEFAULT_CURRENCY);
					final int filterIndex = c.getColumnIndex(TripsTable.COLUMN_FILTERS);
					do {
						final String name = c.getString(nameIndex);
						final long from = c.getLong(fromIndex);
						final long to = c.getLong(toIndex);
						final String fromTimeZone = c.getString(fromTimeZoneIndex);
						final String toTimeZone = c.getString(toTimeZoneIndex);
						// final String price = c.getString(priceIndex);
						final float miles = c.getFloat(milesIndex);
						final String comment = c.getString(commentIndex);
						final String defaultCurrency = c.getString(defaultCurrencyIndex);
						final String filterJson = c.getString(filterIndex);
						qc = db.rawQuery(CURR_CNT_QUERY, new String[] { name });
						int cnt;
						String curr = MULTI_CURRENCY;
						if (qc != null) {
							if (qc.moveToFirst() && qc.getColumnCount() > 0) {
								cnt = qc.getInt(0);
								if (cnt == 1) {
									curr = qc.getString(1);
								}
								else if (cnt == 0) {
									curr = mPersistenceManager.getPreferences().getDefaultCurreny();
								}
							}
							qc.close();
						}
						TripRow.Builder builder = new TripRow.Builder();
						trips[c.getPosition()] = builder.setDirectory(mPersistenceManager.getStorageManager().getFile(name)).setStartDate(from).setEndDate(to).setStartTimeZone(fromTimeZone).setEndTimeZone(toTimeZone)
						// .setPrice(price)
						.setCurrency(curr).setMileage(miles).setComment(comment).setFilter(filterJson).setDefaultCurrency(defaultCurrency, mPersistenceManager.getPreferences().getDefaultCurreny()).setSourceAsCache().build();
						getTripPriceAndDailyPrice(trips[c.getPosition()]);
					}
					while (c.moveToNext());
					return trips;
				}
				else {
					trips = new TripRow[0];
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
				if (qc != null && !qc.isClosed()) {
					qc.close();
				}
			}
			return trips;
		}
	}

	private class GetTripsWorker extends AsyncTask<Void, Void, TripRow[]> {

		private boolean mIsDatabaseCorrupt = false;

		@Override
		protected TripRow[] doInBackground(Void... params) {
			try {
				return getTripsHelper();
			}
			catch (SQLiteDatabaseCorruptException e) {
				mIsDatabaseCorrupt = true;
				return new TripRow[0];
			}
		}

		@Override
		protected void onPostExecute(TripRow[] result) {
			if (mIsDatabaseCorrupt) {
				if (mTripRowListener != null) {
					mTripRowListener.onSQLCorruptionException();
				}
			}
			else {
				synchronized (mTripCacheLock) {
					mAreTripsValid = true;
					mTripsCache = result;
				}
				if (mTripRowListener != null) {
					mTripRowListener.onTripRowsQuerySuccess(result);
				}
			}
		}

	}

	public List<CharSequence> getTripNames() {
		TripRow[] trips = getTripsSerial();
		final ArrayList<CharSequence> tripNames = new ArrayList<CharSequence>(trips.length);
		for (int i = 0; i < trips.length; i++) {
			tripNames.add(trips[i].getName());
		}
		return tripNames;
	}

	public List<CharSequence> getTripNames(TripRow tripToExclude) {
		TripRow[] trips = getTripsSerial();
		final ArrayList<CharSequence> tripNames = new ArrayList<CharSequence>(trips.length - 1);
		for (int i = 0; i < trips.length; i++) {
			TripRow trip = trips[i];
			if (!trip.equals(tripToExclude)) {
				tripNames.add(trip.getName());
			}
		}
		return tripNames;
	}

	public final TripRow getTripByName(final String name) {
		if (name == null || name.length() == 0) {
			return null;
		}
		synchronized (mTripCacheLock) {
			if (mAreTripsValid) {
				for (int i = 0; i < mTripsCache.length; i++) {
					if (mTripsCache[i].getName().equals(name)) {
						return mTripsCache[i];
					}
				}
			}
		}
		SQLiteDatabase db = null;
		Cursor c = null, qc = null;
		synchronized (mDatabaseLock) {
			try {
				db = this.getReadableDatabase();
				c = db.query(TripsTable.TABLE_NAME, null, TripsTable.COLUMN_NAME + " = ?", new String[] { name }, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int fromIndex = c.getColumnIndex(TripsTable.COLUMN_FROM);
					final int toIndex = c.getColumnIndex(TripsTable.COLUMN_TO);
					final int fromTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_FROM_TIMEZONE);
					final int toTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_TO_TIMEZONE);
					// final int priceIndex = c.getColumnIndex(TripsTable.COLUMN_PRICE);
					final int milesIndex = c.getColumnIndex(TripsTable.COLUMN_MILEAGE);
					final int commentIndex = c.getColumnIndex(TripsTable.COLUMN_COMMENT);
					final int defaultCurrencyIndex = c.getColumnIndex(TripsTable.COLUMN_DEFAULT_CURRENCY);
					final int filterIndex = c.getColumnIndex(TripsTable.COLUMN_FILTERS);
					final long from = c.getLong(fromIndex);
					final long to = c.getLong(toIndex);
					final String fromTimeZone = c.getString(fromTimeZoneIndex);
					final String toTimeZone = c.getString(toTimeZoneIndex);
					final float miles = c.getFloat(milesIndex);
					// final String price = c.getString(priceIndex);
					final String comment = c.getString(commentIndex);
					final String defaultCurrency = c.getString(defaultCurrencyIndex);
					final String filterJson = c.getString(filterIndex);
					qc = db.rawQuery(CURR_CNT_QUERY, new String[] { name });
					int cnt;
					String curr = MULTI_CURRENCY;
					;
					if (qc != null && qc.moveToFirst() && qc.getColumnCount() > 0) {
						cnt = qc.getInt(0);
						if (cnt == 1) {
							curr = qc.getString(1);
						}
						else if (cnt == 0) {
							curr = mPersistenceManager.getPreferences().getDefaultCurreny();
						}
					}
					TripRow.Builder builder = new TripRow.Builder();
					TripRow tripRow = builder.setDirectory(mPersistenceManager.getStorageManager().getFile(name)).setStartDate(from).setEndDate(to).setStartTimeZone(fromTimeZone).setEndTimeZone(toTimeZone)
					// .setPrice(price)
					.setCurrency(curr).setMileage(miles).setComment(comment).setFilter(filterJson).setDefaultCurrency(defaultCurrency, mPersistenceManager.getPreferences().getDefaultCurreny()).setSourceAsCache().build();
					getTripPriceAndDailyPrice(tripRow);
					return tripRow;
				}
				else {
					return null;
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
				if (qc != null) {
					qc.close();
				}
			}
		}
	}

	// Returns the trip on success. Null otherwise
	public final TripRow insertTripSerial(File dir, Date from, Date to, String comment, String defaultCurrencyCode) throws SQLException {
		TripRow trip = insertTripHelper(dir, from, to, comment, defaultCurrencyCode);
		if (trip != null) {
			synchronized (mTripCacheLock) {
				mAreTripsValid = false;
			}
		}
		return trip;
	}

	public void insertTripParallel(File dir, Date from, Date to, String comment, String defaultCurrencyCode) {
		if (mTripRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No TripRowListener was registered.");
			}
		}
		(new InsertTripRowWorker(dir, from, to, comment, defaultCurrencyCode)).execute(new Void[0]);
	}

	private TripRow insertTripHelper(File dir, Date from, Date to, String comment, String defaultCurrencyCode) throws SQLException {
		ContentValues values = new ContentValues(3);
		values.put(TripsTable.COLUMN_NAME, dir.getName());
		values.put(TripsTable.COLUMN_FROM, from.getTime());
		values.put(TripsTable.COLUMN_TO, to.getTime());
		values.put(TripsTable.COLUMN_FROM_TIMEZONE, TimeZone.getDefault().getID());
		values.put(TripsTable.COLUMN_TO_TIMEZONE, TimeZone.getDefault().getID());
		values.put(TripsTable.COLUMN_COMMENT, comment);
		values.put(TripsTable.COLUMN_DEFAULT_CURRENCY, defaultCurrencyCode);
		TripRow toReturn = null;
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			if (values == null || db.insertOrThrow(TripsTable.TABLE_NAME, null, values) == -1) {
				return null;
			}
			else {
				toReturn = (new TripRow.Builder()).setDirectory(dir).setStartDate(from).setEndDate(to).setStartTimeZone(TimeZone.getDefault()).setEndTimeZone(TimeZone.getDefault()).setCurrency(defaultCurrencyCode).setComment(comment).setDefaultCurrency(defaultCurrencyCode).setSourceAsCache().build();
			}
		}
		if (this.getReadableDatabase() != null) {
			String databasePath = this.getReadableDatabase().getPath();
			if (!TextUtils.isEmpty(databasePath)) {
				backUpDatabase(databasePath);
			}
		}
		return toReturn;
	}

	private class InsertTripRowWorker extends AsyncTask<Void, Void, TripRow> {

		private final File mDir;
		private final Date mFrom, mTo;
		private final String mComment, mDefaultCurrencyCode;
		private SQLException mException;

		public InsertTripRowWorker(final File dir, final Date from, final Date to, final String comment, final String defaultCurrencyCode) {
			mDir = dir;
			mFrom = from;
			mTo = to;
			mComment = comment;
			mDefaultCurrencyCode = defaultCurrencyCode;
			mException = null;
		}

		@Override
		protected TripRow doInBackground(Void... params) {
			try {
				return insertTripHelper(mDir, mFrom, mTo, mComment, mDefaultCurrencyCode);
			}
			catch (SQLException ex) {
				mException = ex;
				return null;
			}
		}

		@Override
		protected void onPostExecute(TripRow result) {
			if (result != null) {
				synchronized (mTripCacheLock) {
					mAreTripsValid = false;
				}
				if (mTripRowListener != null) {
					mTripRowListener.onTripRowInsertSuccess(result);
				}
			}
			else {
				if (mTripRowListener != null) {
					mTripRowListener.onTripRowInsertFailure(mException, mDir);
				}
			}
		}

	}

	public final TripRow updateTripSerial(TripRow oldTrip, File dir, Date from, Date to, String comment, String defaultCurrencyCode) {
		TripRow trip = updateTripHelper(oldTrip, dir, from, to, comment, defaultCurrencyCode);
		if (trip != null) {
			synchronized (mTripCacheLock) {
				mAreTripsValid = false;
			}
		}
		return trip;
	}

	public void updateTripParallel(TripRow oldTrip, File dir, Date from, Date to, String comment, String defaultCurrencyCode) {
		if (mTripRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No TripRowListener was registered.");
			}
		}
		(new UpdateTripRowWorker(oldTrip, dir, from, to, comment, defaultCurrencyCode)).execute(new Void[0]);
	}

	private TripRow updateTripHelper(TripRow oldTrip, File dir, Date from, Date to, String comment, String defaultCurrencyCode) {
		ContentValues values = new ContentValues(3);
		values.put(TripsTable.COLUMN_NAME, dir.getName());
		values.put(TripsTable.COLUMN_FROM, from.getTime());
		values.put(TripsTable.COLUMN_TO, to.getTime());
		TimeZone startTimeZone = oldTrip.getStartTimeZone();
		TimeZone endTimeZone = oldTrip.getEndTimeZone();
		if (!from.equals(oldTrip.getStartDate())) { // Update time zone if date changed
			startTimeZone = TimeZone.getDefault();
			values.put(TripsTable.COLUMN_FROM_TIMEZONE, startTimeZone.getID());
		}
		if (!to.equals(oldTrip.getEndDate())) { // Update time zone if date changed
			endTimeZone = TimeZone.getDefault();
			values.put(TripsTable.COLUMN_TO_TIMEZONE, endTimeZone.getID());
		}
		values.put(TripsTable.COLUMN_COMMENT, comment);
		values.put(TripsTable.COLUMN_DEFAULT_CURRENCY, defaultCurrencyCode);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				if (values == null || (db.update(TripsTable.TABLE_NAME, values, TripsTable.COLUMN_NAME + " = ?", new String[] { oldTrip.getName() }) == 0)) {
					return null;
				}
				else {
					if (!oldTrip.getName().equalsIgnoreCase(dir.getName())) {
						synchronized (mReceiptCacheLock) {
							if (mReceiptCache.containsKey(oldTrip)) {
								mReceiptCache.remove(oldTrip);
							}
						}
						String oldName = oldTrip.getName();
						String newName = dir.getName();
						ContentValues rcptVals = new ContentValues(1);
						rcptVals.put(ReceiptsTable.COLUMN_PARENT, newName);
						// Update parent
						db.update(ReceiptsTable.TABLE_NAME, rcptVals, ReceiptsTable.COLUMN_PARENT + " = ?", new String[] { oldName }); // Consider
																																		// rollback
																																		// here
					}
					return (new TripRow.Builder()).setDirectory(dir).setStartDate(from).setEndDate(to).setStartTimeZone(startTimeZone).setEndTimeZone(endTimeZone).setCurrency(oldTrip.getCurrency()).setComment(comment).setDefaultCurrency(defaultCurrencyCode, mPersistenceManager.getPreferences().getDefaultCurreny()).setSourceAsCache().build();
				}
			}
			catch (SQLException e) {
				if (BuildConfig.DEBUG) {
					Log.e(TAG, e.toString());
				}
				return null;
			}
		}
	}

	private class UpdateTripRowWorker extends AsyncTask<Void, Void, TripRow> {

		private final File mDir;
		private final Date mFrom, mTo;
		private final String mComment, mDefaultCurrencyCode;
		private final TripRow mOldTrip;

		public UpdateTripRowWorker(TripRow oldTrip, File dir, Date from, Date to, String comment, String defaultCurrencyCode) {
			mOldTrip = oldTrip;
			mDir = dir;
			mFrom = from;
			mTo = to;
			mComment = comment;
			mDefaultCurrencyCode = defaultCurrencyCode;
		}

		@Override
		protected TripRow doInBackground(Void... params) {
			return updateTripHelper(mOldTrip, mDir, mFrom, mTo, mComment, mDefaultCurrencyCode);
		}

		@Override
		protected void onPostExecute(TripRow result) {
			if (result != null) {
				synchronized (mTripCacheLock) {
					mAreTripsValid = false;
				}
				if (mTripRowListener != null) {
					mTripRowListener.onTripRowUpdateSuccess(result);
				}
			}
			else {
				if (mTripRowListener != null) {
					mTripRowListener.onTripRowUpdateFailure(result, mOldTrip, mDir);
				}
			}
		}

	}

	public boolean deleteTripSerial(TripRow trip) {
		if (mTripRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No TripRowListener was registered.");
			}
		}
		boolean success = deleteTripHelper(trip);
		if (success) {
			synchronized (mTripCacheLock) {
				mAreTripsValid = false;
			}
		}
		return success;
	}

	public void deleteTripParallel(TripRow trip) {
		(new DeleteTripRowWorker()).execute(trip);
	}

	private boolean deleteTripHelper(TripRow trip) {
		boolean success = false;
		SQLiteDatabase db = null;
		db = this.getWritableDatabase();
		// Delete all child receipts (technically ON DELETE CASCADE should handle this, but i'm not certain)
		synchronized (mDatabaseLock) {
			// TODO: Fix errors when the disk is not yet mounted
			success = (db.delete(ReceiptsTable.TABLE_NAME, ReceiptsTable.COLUMN_PARENT + " = ?", new String[] { trip.getName() }) >= 0);
		}
		if (success) {
			synchronized (mReceiptCacheLock) {
				mReceiptCache.remove(trip);
			}
		}
		else {
			return false;
		}
		synchronized (mDatabaseLock) {
			success = (db.delete(TripsTable.TABLE_NAME, TripsTable.COLUMN_NAME + " = ?", new String[] { trip.getName() }) > 0);
		}
		return success;
	}

	private class DeleteTripRowWorker extends AsyncTask<TripRow, Void, Boolean> {

		private TripRow mOldTrip;

		@Override
		protected Boolean doInBackground(TripRow... params) {
			if (params == null || params.length == 0) {
				return false;
			}
			mOldTrip = params[0];
			return deleteTripHelper(mOldTrip);
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (result) {
				synchronized (mTripCacheLock) {
					mAreTripsValid = false;
				}
			}
			if (mTripRowListener != null) {
				if (result) {
					mTripRowListener.onTripDeleteSuccess(mOldTrip);
				}
				else {
					mTripRowListener.onTripDeleteFailure();
				}
			}
		}

	}

	public final boolean addMiles(final TripRow trip, final String delta) {
		try {
			final SQLiteDatabase db = this.getReadableDatabase();

			DecimalFormat format = new DecimalFormat();
			format.setMaximumFractionDigits(2);
			format.setMinimumFractionDigits(2);
			format.setGroupingUsed(false);

			final float currentMiles = trip.getMileage();
			final float deltaMiles = format.parse(delta).floatValue();
			float total = currentMiles + deltaMiles;
			if (total < 0) {
				total = 0;
			}
			ContentValues values = new ContentValues(1);
			values.put(TripsTable.COLUMN_MILEAGE, total);
			trip.setMileage(total);
			return (db.update(TripsTable.TABLE_NAME, values, TripsTable.COLUMN_NAME + " = ?", new String[] { trip.getName() }) > 0);
		}
		catch (NumberFormatException e) {
			return false;
		}
		catch (ParseException e) {
			return false;
		}
	}

	/**
	 * This class is not synchronized! Sync outside of it
	 * 
	 * @param trip
	 * @return
	 */
	private final void getTripPriceAndDailyPrice(final TripRow trip) {
		queryTripPrice(trip);
		queryTripDailyPrice(trip);
	}

	/**
	 * Queries the trips price and updates this object. This class is not synchronized! Sync outside of it
	 * 
	 * @param trip
	 *            the trip, which will be updated
	 */
	private final void queryTripPrice(final TripRow trip) {
		SQLiteDatabase db = null;
		Cursor c = null;
		try {
			mAreTripsValid = false;
			db = this.getReadableDatabase();

			String selection = ReceiptsTable.COLUMN_PARENT + "= ?";
			if (mPersistenceManager.getPreferences().onlyIncludeExpensableReceiptsInReports()) {
				selection += " AND " + ReceiptsTable.COLUMN_EXPENSEABLE + " = 1";
			}
			// Get the Trip's total Price
			c = db.query(ReceiptsTable.TABLE_NAME, new String[] { "SUM(" + ReceiptsTable.COLUMN_PRICE + ")" }, selection, new String[] { trip.getName() }, null, null, null);
			if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
				final double sum = c.getDouble(0);
				trip.setPrice(sum);
			}
		}
		finally {
			if (c != null) {
				c.close();
			}
		}
	}

	/**
	 * Queries the trips daily total price and updates this object. This class is not synchronized! Sync outside of it
	 * 
	 * @param trip
	 *            the trip, which will be updated
	 */
	private final void queryTripDailyPrice(final TripRow trip) {
		SQLiteDatabase db = null;
		Cursor priceCursor = null;
		try {
			db = this.getReadableDatabase();

			// Build a calendar for the start of today
			final Time now = new Time();
			now.setToNow();
			final Calendar startCalendar = Calendar.getInstance();
			startCalendar.setTimeInMillis(now.toMillis(false));
			startCalendar.setTimeZone(TimeZone.getDefault());
			startCalendar.set(Calendar.HOUR_OF_DAY, 0);
			startCalendar.set(Calendar.MINUTE, 0);
			startCalendar.set(Calendar.SECOND, 0);
			startCalendar.set(Calendar.MILLISECOND, 0);

			// Build a calendar for the end date
			final Calendar endCalendar = Calendar.getInstance();
			endCalendar.setTimeInMillis(now.toMillis(false));
			endCalendar.setTimeZone(TimeZone.getDefault());
			endCalendar.set(Calendar.HOUR_OF_DAY, 23);
			endCalendar.set(Calendar.MINUTE, 59);
			endCalendar.set(Calendar.SECOND, 59);
			endCalendar.set(Calendar.MILLISECOND, 999);

			// Set the timers
			final long startTime = startCalendar.getTimeInMillis();
			final long endTime = endCalendar.getTimeInMillis();
			String selection = ReceiptsTable.COLUMN_PARENT + "= ? AND " + ReceiptsTable.COLUMN_DATE + " >= ? AND " + ReceiptsTable.COLUMN_DATE + " <= ?";
			if (mPersistenceManager.getPreferences().onlyIncludeExpensableReceiptsInReports()) {
				selection += " AND " + ReceiptsTable.COLUMN_EXPENSEABLE + " = 1";
			}

			priceCursor = db.query(ReceiptsTable.TABLE_NAME, new String[] { "SUM(" + ReceiptsTable.COLUMN_PRICE + ")" }, selection, new String[] { trip.getName(), Long.toString(startTime), Long.toString(endTime) }, null, null, null);

			if (priceCursor != null && priceCursor.moveToFirst() && priceCursor.getColumnCount() > 0) {
				final double dailyTotal = priceCursor.getDouble(0);
				trip.setDailySubTotal(dailyTotal);
			}
		}
		finally { // Close the cursor to avoid memory leaks
			if (priceCursor != null) {
				priceCursor.close();
			}
		}
	}

	private final void updateTripPrice(final TripRow trip) {
		synchronized (mDatabaseLock) {
			mAreTripsValid = false;
			queryTripPrice(trip);
			queryTripDailyPrice(trip);
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// ReceiptRow Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public void registerReceiptRowListener(ReceiptRowListener listener) {
		mReceiptRowListener = listener;
	}

	public void unregisterReceiptRowListener() {
		mReceiptRowListener = null;
	}

	public List<ReceiptRow> getReceiptsSerial(final TripRow trip) {
		synchronized (mReceiptCacheLock) {
			if (mReceiptCache.containsKey(trip)) {
				return mReceiptCache.get(trip);
			}
		}
		return this.getReceiptsHelper(trip, true);
	}

	public List<ReceiptRow> getReceiptsSerial(final TripRow trip, final boolean desc) { // Only the email writer should
		return getReceiptsHelper(trip, desc);
	}

	public void getReceiptsParallel(final TripRow trip) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		synchronized (mReceiptCacheLock) {
			if (mReceiptCache.containsKey(trip)) { // only cache the default way (otherwise we get into issues with asc
													// v desc)
				if (mReceiptRowListener != null) {
					mReceiptRowListener.onReceiptRowsQuerySuccess(mReceiptCache.get(trip));
				}
				return;
			}
		}
		(new GetReceiptsWorker()).execute(trip);
	}

	/**
	 * Gets all receipts associated with a particular trip
	 * 
	 * @param trip
	 *            - the trip
	 * @param silence
	 *            - silences the result (so no listeners will be alerted)
	 */
	public void getReceiptsParallel(final TripRow trip, boolean silence) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		synchronized (mReceiptCacheLock) {
			if (mReceiptCache.containsKey(trip)) { // only cache the default way (otherwise we get into issues with asc
													// v desc)
				if (mReceiptRowListener != null) {
					mReceiptRowListener.onReceiptRowsQuerySuccess(mReceiptCache.get(trip));
				}
				return;
			}
		}
		(new GetReceiptsWorker(true)).execute(trip);
	}

	private final List<ReceiptRow> getReceiptsHelper(final TripRow trip, final boolean desc) {
		List<ReceiptRow> receipts;
		if (trip == null) {
			return new ArrayList<ReceiptRow>();
		}
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(ReceiptsTable.TABLE_NAME, null, ReceiptsTable.COLUMN_PARENT + "= ?", new String[] { trip.getName() }, null, null, ReceiptsTable.COLUMN_DATE + ((desc) ? " DESC" : " ASC"));
				if (c != null && c.moveToFirst()) {
					receipts = new ArrayList<ReceiptRow>(c.getCount());
					final int idIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ID);
					final int pathIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PATH);
					final int nameIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NAME);
					final int categoryIndex = c.getColumnIndex(ReceiptsTable.COLUMN_CATEGORY);
					final int priceIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PRICE);
					final int taxIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TAX);
					final int dateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_DATE);
					final int timeZoneIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TIMEZONE);
					final int commentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_COMMENT);
					final int expenseableIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXPENSEABLE);
					final int currencyIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ISO4217);
					final int fullpageIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE);
					final int paymentMethodIdIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID);
					final int extra_edittext_1_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1);
					final int extra_edittext_2_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2);
					final int extra_edittext_3_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3);
					do {
						final int id = c.getInt(idIndex);
						final String path = c.getString(pathIndex);
						final String name = c.getString(nameIndex);
						final String category = c.getString(categoryIndex);
						final double priceDouble = c.getDouble(priceIndex);
						final double taxDouble = c.getDouble(taxIndex);
						final String priceString = c.getString(priceIndex);
						final String taxString = c.getString(taxIndex);
						final long date = c.getLong(dateIndex);
						final String timezone = (timeZoneIndex > 0) ? c.getString(timeZoneIndex) : null;
						final String comment = c.getString(commentIndex);
						final boolean expensable = c.getInt(expenseableIndex) > 0;
						final String currency = c.getString(currencyIndex);
						final boolean fullpage = !(c.getInt(fullpageIndex) > 0);
						final int paymentMethodId = c.getInt(paymentMethodIdIndex); // Not using a join, since we need
						final String extra_edittext_1 = c.getString(extra_edittext_1_Index);
						final String extra_edittext_2 = c.getString(extra_edittext_2_Index);
						final String extra_edittext_3 = c.getString(extra_edittext_3_Index);
						File img = null;
						if (!path.equalsIgnoreCase(DatabaseHelper.NO_DATA)) {
							img = mPersistenceManager.getStorageManager().getFile(trip.getDirectory(), path);
						}
						ReceiptRow.Builder builder = new ReceiptRow.Builder(id);
						builder.setTrip(trip).setName(name).setCategory(category).setImage(img).setDate(date).setTimeZone(timezone).setComment(comment).setIsExpenseable(expensable).setCurrency(currency).setIsFullPage(fullpage).setIndex(c.getPosition() + 1).setPaymentMethod(findPaymentMethodById(paymentMethodId)).setExtraEditText1(extra_edittext_1).setExtraEditText2(extra_edittext_2).setExtraEditText3(extra_edittext_3);
						/**
						 * Please note that a very frustrating bug exists here. Android cursors only return the first 6
						 * characters of a price string if that string contains a '.' character. It returns all of them
						 * if not. This means we'll break for prices over 5 digits unless we are using a comma separator, 
						 * which we'd do in the EU. Stupid check below to un-break this. Stupid Android.
						 * 
						 * TODO: Longer term, everything should be saved with a decimal point
						 * https://code.google.com/p/android/issues/detail?id=22219
						 */
						if (!TextUtils.isEmpty(priceString) && priceString.contains(",")) {
							builder.setPrice(priceString);
						}
						else {
							builder.setPrice(priceDouble);
						}
						if (!TextUtils.isEmpty(taxString) && taxString.contains(",")) {
							builder.setTax(taxString);
						}
						else {
							builder.setTax(taxDouble);
						}
						receipts.add(builder.build());
					}
					while (c.moveToNext());
				}
				else {
					receipts = new ArrayList<ReceiptRow>();
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
		synchronized (mReceiptCacheLock) {
			if (desc) {
				mReceiptCache.put(trip, receipts);
			}
		}
		return receipts;
	}

	private class GetReceiptsWorker extends AsyncTask<TripRow, Void, List<ReceiptRow>> {

		private final boolean mSilence;

		public GetReceiptsWorker() {
			mSilence = false;
		}

		public GetReceiptsWorker(boolean silence) {
			mSilence = silence;
		}

		@Override
		protected List<ReceiptRow> doInBackground(TripRow... params) {
			if (params == null || params.length == 0) {
				return new ArrayList<ReceiptRow>();
			}
			TripRow trip = params[0];
			return getReceiptsHelper(trip, true);
		}

		@Override
		protected void onPostExecute(List<ReceiptRow> result) {
			if (mReceiptRowListener != null && !mSilence) {
				mReceiptRowListener.onReceiptRowsQuerySuccess(result);
			}
		}

	}

	public final ReceiptRow getReceiptByID(final int id) {
		if (id <= 0) {
			return null;
		}
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(ReceiptsTable.TABLE_NAME, null, ReceiptsTable.COLUMN_ID + "= ?", new String[] { Integer.toString(id) }, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int pathIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PATH);
					final int parentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PARENT);
					final int nameIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NAME);
					final int categoryIndex = c.getColumnIndex(ReceiptsTable.COLUMN_CATEGORY);
					final int priceIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PRICE);
					final int taxIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TAX);
					final int dateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_DATE);
					final int timeZoneIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TIMEZONE);
					final int commentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_COMMENT);
					final int expenseableIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXPENSEABLE);
					final int currencyIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ISO4217);
					final int fullpageIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE);
					final int paymentMethodIdIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID);
					final int extra_edittext_1_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1);
					final int extra_edittext_2_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2);
					final int extra_edittext_3_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3);
					final String path = c.getString(pathIndex);
					final String parent = c.getString(parentIndex);
					final String name = c.getString(nameIndex);
					final String category = c.getString(categoryIndex);
					final String price = c.getString(priceIndex);
					final String tax = c.getString(taxIndex);
					final long date = c.getLong(dateIndex);
					final String timezone = c.getString(timeZoneIndex);
					final String comment = c.getString(commentIndex);
					final boolean expensable = c.getInt(expenseableIndex) > 0;
					final String currency = c.getString(currencyIndex);
					final boolean fullpage = !(c.getInt(fullpageIndex) > 0);
					final int paymentMethodId = c.getInt(paymentMethodIdIndex); // Not using a join, since we need the
					final String extra_edittext_1 = c.getString(extra_edittext_1_Index);
					final String extra_edittext_2 = c.getString(extra_edittext_2_Index);
					final String extra_edittext_3 = c.getString(extra_edittext_3_Index);
					File img = null;
					if (!path.equalsIgnoreCase(DatabaseHelper.NO_DATA)) {
						final StorageManager storageManager = mPersistenceManager.getStorageManager();
						img = storageManager.getFile(storageManager.getFile(parent), path);
					}
					ReceiptRow.Builder builder = new ReceiptRow.Builder(id);
					return builder.setTrip(getTripByName(parent)).setName(name).setCategory(category).setImage(img).setDate(date).setTimeZone(timezone).setComment(comment).setPrice(price).setTax(tax).setIsExpenseable(expensable).setCurrency(currency).setIsFullPage(fullpage).setPaymentMethod(findPaymentMethodById(paymentMethodId)).setExtraEditText1(extra_edittext_1).setExtraEditText2(extra_edittext_2).setExtraEditText3(extra_edittext_3).build();
				}
				else {
					return null;
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public ReceiptRow insertReceiptSerial(TripRow parent, ReceiptRow receipt) throws SQLException {
		return insertReceiptSerial(parent, receipt, receipt.getFile());
	}

	public ReceiptRow insertReceiptSerial(TripRow parent, ReceiptRow receipt, File newFile) throws SQLException {
		return insertReceiptHelper(parent, newFile, receipt.getName(), receipt.getCategory(), receipt.getDate(), receipt.getTimeZone(), receipt.getComment(), receipt.getPrice(), receipt.getTax(), receipt.isExpensable(), receipt.getCurrencyCode(), receipt.isFullPage(), receipt.getPaymentMethod(), receipt.getExtraEditText1(), receipt.getExtraEditText2(), receipt.getExtraEditText3());
	}

	public ReceiptRow insertReceiptSerial(TripRow trip, File img, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
			String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) throws SQLException {

		return insertReceiptHelper(trip, img, name, category, date, null, comment, price, tax, expensable, currency, fullpage, method, extra_edittext_1, extra_edittext_2, extra_edittext_3);
	}

	public void insertReceiptParallel(TripRow trip, File img, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
			String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		(new InsertReceiptWorker(trip, img, name, category, date, comment, price, tax, expensable, currency, fullpage, method, extra_edittext_1, extra_edittext_2, extra_edittext_3)).execute(new Void[0]);
	}

	private ReceiptRow insertReceiptHelper(TripRow trip, File img, String name, String category, Date date, TimeZone timeZone, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage,
			PaymentMethod method, String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) throws SQLException {

		final int rcptNum = this.getReceiptsSerial(trip).size() + 1; // Use this to order things more properly
		StringBuilder stringBuilder = new StringBuilder(rcptNum + "_");
		ContentValues values = new ContentValues(10);

		values.put(ReceiptsTable.COLUMN_PARENT, trip.getName());
		if (name.length() > 0) {
			stringBuilder.append(name.trim());
			values.put(ReceiptsTable.COLUMN_NAME, name.trim());
		}
		values.put(ReceiptsTable.COLUMN_CATEGORY, category);
		if (date == null) {
			if (mNow == null) {
				mNow = new Time();
			}
			mNow.setToNow();
			values.put(ReceiptsTable.COLUMN_DATE, mNow.toMillis(false));
		}
		else {
			values.put(ReceiptsTable.COLUMN_DATE, date.getTime() + rcptNum); // In theory, this hack may cause issue if
																				// there are > 1000 receipts. I imagine
																				// other bugs will arise before this
																				// point
		}
		if (timeZone == null) {
			timeZone = TimeZone.getDefault();
			values.put(ReceiptsTable.COLUMN_TIMEZONE, TimeZone.getDefault().getID());
		}
		else {
			values.put(ReceiptsTable.COLUMN_TIMEZONE, timeZone.getID());
		}
		values.put(ReceiptsTable.COLUMN_COMMENT, comment);
		values.put(ReceiptsTable.COLUMN_EXPENSEABLE, expensable);
		values.put(ReceiptsTable.COLUMN_ISO4217, currency);
		values.put(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE, !fullpage);
		if (price.length() > 0) {
			values.put(ReceiptsTable.COLUMN_PRICE, price);
		}
		if (tax.length() > 0) {
			values.put(ReceiptsTable.COLUMN_TAX, tax);
			// Extras
		}

		if (img == null) {
			values.put(ReceiptsTable.COLUMN_PATH, NO_DATA);
		}
		else {
			stringBuilder.append('.').append(StorageManager.getExtension(img));
			String newName = stringBuilder.toString();
			File file = mPersistenceManager.getStorageManager().getFile(trip.getDirectory(), newName);
			if (!file.exists()) { // If this file doesn't exist, let's rename our current one
				if (BuildConfig.DEBUG) {
					Log.e(TAG, "Changing image name from: " + img.getName() + " to: " + newName);
				}
				img = mPersistenceManager.getStorageManager().rename(img, newName); // Returns oldFile on failure
			}
			values.put(ReceiptsTable.COLUMN_PATH, img.getName());
		}

		if (method != null) {
			values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, method.getId());
		}
		else {
			final Integer integer = null;
			values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, integer);
		}

		if (extra_edittext_1 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, NO_DATA);
		}
		else {
			if (extra_edittext_1.equalsIgnoreCase("null")) {
				extra_edittext_1 = ""; // I don't know why I hard-coded null here -- NO_DATA = "null"
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, extra_edittext_1);
		}
		if (extra_edittext_2 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, NO_DATA);
		}
		else {
			if (extra_edittext_2.equalsIgnoreCase("null")) {
				extra_edittext_2 = ""; // I don't know why I hard-coded null here -- NO_DATA = "null"
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, extra_edittext_2);
		}
		if (extra_edittext_3 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, NO_DATA);
		}
		else {
			if (extra_edittext_3.equalsIgnoreCase("null")) {
				extra_edittext_3 = ""; // I don't know why I hard-coded null here -- NO_DATA = "null"
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, extra_edittext_3);
		}

		ReceiptRow insertReceipt;
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getWritableDatabase();
				if (db.insertOrThrow(ReceiptsTable.TABLE_NAME, null, values) == -1) {
					insertReceipt = null;
				}
				else {
					this.updateTripPrice(trip);
					if (mReceiptCache.containsKey(trip)) {
						mReceiptCache.remove(trip);
					}
					c = db.rawQuery("SELECT last_insert_rowid()", null);
					if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
						final int id = c.getInt(0);
						date.setTime(date.getTime() + rcptNum);
						ReceiptRow.Builder builder = new ReceiptRow.Builder(id);
						insertReceipt = builder.setTrip(trip).setName(name).setCategory(category).setImage(img).setDate(date).setTimeZone(timeZone).setComment(comment).setPrice(price).setTax(tax).setIndex(rcptNum).setIsExpenseable(expensable).setCurrency(currency).setIsFullPage(fullpage).setPaymentMethod(method).setExtraEditText1(extra_edittext_1).setExtraEditText2(extra_edittext_2).setExtraEditText3(extra_edittext_3).build();
					}
					else {
						insertReceipt = null;
					}
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
		if (insertReceipt != null) {
			synchronized (mReceiptCacheLock) {
				if (mReceiptCache.containsKey(trip)) {
					mReceiptCache.remove(trip);
				}
				mNextReceiptAutoIncrementId = -1;
			}
		}
		return insertReceipt;
	}

	private class InsertReceiptWorker extends AsyncTask<Void, Void, ReceiptRow> {

		private final TripRow mTrip;
		private final File mImg;
		private final String mName, mCategory, mComment, mPrice, mTax, mCurrency, mExtra_edittext_1, mExtra_edittext_2, mExtra_edittext_3;
		private final Date mDate;
		private final PaymentMethod mPaymentMethod;
		private final boolean mExpensable, mFullpage;
		private SQLException mException;

		public InsertReceiptWorker(TripRow trip, File img, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
				String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {
			mTrip = trip;
			mImg = img;
			mName = name;
			mCategory = category;
			mDate = date;
			mComment = comment;
			mPrice = price;
			mTax = tax;
			mExpensable = expensable;
			mCurrency = currency;
			mFullpage = fullpage;
			mPaymentMethod = method;
			mExtra_edittext_1 = extra_edittext_1;
			mExtra_edittext_2 = extra_edittext_2;
			mExtra_edittext_3 = extra_edittext_3;
		}

		@Override
		protected ReceiptRow doInBackground(Void... params) {
			try {
				return insertReceiptHelper(mTrip, mImg, mName, mCategory, mDate, null, mComment, mPrice, mTax, mExpensable, mCurrency, mFullpage, mPaymentMethod, mExtra_edittext_1, mExtra_edittext_2, mExtra_edittext_3);
			}
			catch (SQLException ex) {
				mException = ex;
				return null;
			}
		}

		@Override
		protected void onPostExecute(ReceiptRow result) {
			if (mReceiptRowListener != null) {
				if (result != null) {
					mReceiptRowListener.onReceiptRowInsertSuccess(result);
				}
				else {
					mReceiptRowListener.onReceiptRowInsertFailure(mException);
				}
			}
		}
	}

	public ReceiptRow updateReceiptSerial(ReceiptRow oldReceipt, TripRow trip, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
			String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {
		return updateReceiptHelper(oldReceipt, trip, name, category, date, comment, price, tax, expensable, currency, fullpage, method, extra_edittext_1, extra_edittext_2, extra_edittext_3);
	}

	public void updateReceiptParallel(ReceiptRow oldReceipt, TripRow trip, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
			String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {

		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		(new UpdateReceiptWorker(oldReceipt, trip, name, category, date, comment, price, tax, expensable, currency, fullpage, method, extra_edittext_1, extra_edittext_2, extra_edittext_3)).execute(new Void[0]);
	}

	private ReceiptRow updateReceiptHelper(ReceiptRow oldReceipt, TripRow trip, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
			String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {

		ContentValues values = new ContentValues(10);
		values.put(ReceiptsTable.COLUMN_NAME, name.trim());
		values.put(ReceiptsTable.COLUMN_CATEGORY, category);
		TimeZone timeZone = oldReceipt.getTimeZone();
		if (!date.equals(oldReceipt.getDate())) { // Update the timezone if the date changes
			timeZone = TimeZone.getDefault();
			values.put(ReceiptsTable.COLUMN_TIMEZONE, timeZone.getID());
		}
		if ((date.getTime() % 3600000) == 0) {
			values.put(ReceiptsTable.COLUMN_DATE, date.getTime() + oldReceipt.getId());
		}
		else {
			values.put(ReceiptsTable.COLUMN_DATE, date.getTime());
		}
		values.put(ReceiptsTable.COLUMN_COMMENT, comment);
		if (price.length() > 0) {
			values.put(ReceiptsTable.COLUMN_PRICE, price);
		}
		if (tax.length() > 0) {
			values.put(ReceiptsTable.COLUMN_TAX, tax);
		}
		values.put(ReceiptsTable.COLUMN_EXPENSEABLE, expensable);
		values.put(ReceiptsTable.COLUMN_ISO4217, currency);
		values.put(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE, !fullpage);
		// //Extras

		if (method != null) {
			values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, method.getId());
		}
		else {
			final Integer integer = null;
			values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, integer);
		}

		if (extra_edittext_1 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, NO_DATA);
		}
		else {
			if (extra_edittext_1.equalsIgnoreCase("null")) {
				extra_edittext_1 = "";
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, extra_edittext_1);
		}
		if (extra_edittext_2 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, NO_DATA);
		}
		else {
			if (extra_edittext_2.equalsIgnoreCase("null")) {
				extra_edittext_2 = "";
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, extra_edittext_2);
		}
		if (extra_edittext_3 == null) {
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, NO_DATA);
		}
		else {
			if (extra_edittext_3.equalsIgnoreCase("null")) {
				extra_edittext_3 = "";
			}
			values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, extra_edittext_3);
		}

		ReceiptRow updatedReceipt;
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				if (values == null || (db.update(ReceiptsTable.TABLE_NAME, values, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(oldReceipt.getId()) }) == 0)) {
					updatedReceipt = null;
				}
				else {
					this.updateTripPrice(trip);
					ReceiptRow.Builder builder = new ReceiptRow.Builder(oldReceipt.getId());
					updatedReceipt = builder.setTrip(trip).setName(name).setCategory(category).setFile(oldReceipt.getFile()).setDate(date).setTimeZone(timeZone).setComment(comment).setPrice(price).setTax(tax).setIsExpenseable(expensable).setCurrency(currency).setIsFullPage(fullpage).setIndex(oldReceipt.getIndex()).setPaymentMethod(method).setExtraEditText1(extra_edittext_1).setExtraEditText2(extra_edittext_2).setExtraEditText3(extra_edittext_3).build();

				}
			}
			catch (SQLException e) {
				return null;
			}
		}
		synchronized (mReceiptCacheLock) {
			mNextReceiptAutoIncrementId = -1;
			if (updatedReceipt != null) {
				mReceiptCache.remove(trip);
			}
		}
		return updatedReceipt;
	}

	private class UpdateReceiptWorker extends AsyncTask<Void, Void, ReceiptRow> {

		private final ReceiptRow mOldReceipt;
		private final TripRow mTrip;
		private final String mName, mCategory, mComment, mPrice, mTax, mCurrency, mExtra_edittext_1, mExtra_edittext_2, mExtra_edittext_3;
		private final Date mDate;
		private final PaymentMethod mPaymentMethod;
		private final boolean mExpensable, mFullpage;

		public UpdateReceiptWorker(ReceiptRow oldReceipt, TripRow trip, String name, String category, Date date, String comment, String price, String tax, boolean expensable, String currency, boolean fullpage, PaymentMethod method,
				String extra_edittext_1, String extra_edittext_2, String extra_edittext_3) {
			mOldReceipt = oldReceipt;
			mTrip = trip;
			mName = name;
			mCategory = category;
			mDate = date;
			mComment = comment;
			mPrice = price;
			mTax = tax;
			mExpensable = expensable;
			mCurrency = currency;
			mFullpage = fullpage;
			mPaymentMethod = method;
			mExtra_edittext_1 = extra_edittext_1;
			mExtra_edittext_2 = extra_edittext_2;
			mExtra_edittext_3 = extra_edittext_3;
		}

		@Override
		protected ReceiptRow doInBackground(Void... params) {
			return updateReceiptHelper(mOldReceipt, mTrip, mName, mCategory, mDate, mComment, mPrice, mTax, mExpensable, mCurrency, mFullpage, mPaymentMethod, mExtra_edittext_1, mExtra_edittext_2, mExtra_edittext_3);
		}

		@Override
		protected void onPostExecute(ReceiptRow result) {
			if (mReceiptRowListener != null) {
				if (result != null) {
					mReceiptRowListener.onReceiptRowUpdateSuccess(result);
				}
				else {
					mReceiptRowListener.onReceiptRowUpdateFailure();
				}
			}
		}
	}

	public final ReceiptRow updateReceiptFile(final ReceiptRow oldReceipt, final File file) {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getReadableDatabase();
				ContentValues values = new ContentValues(1);
				if (file == null) {
					values.put(ReceiptsTable.COLUMN_PATH, NO_DATA);
				}
				else {
					values.put(ReceiptsTable.COLUMN_PATH, file.getName());
				}
				if (values == null || (db.update(ReceiptsTable.TABLE_NAME, values, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(oldReceipt.getId()) }) == 0)) {
					return null;
				}
				else {
					synchronized (mReceiptCacheLock) {
						mNextReceiptAutoIncrementId = -1;
						mReceiptCache.remove(oldReceipt.getTrip());
					}
					oldReceipt.setFile(file);
					return oldReceipt;
				}
			}
			catch (SQLException e) {
				return null;
			}
		}
	}

	public boolean copyReceiptSerial(ReceiptRow receipt, TripRow newTrip) {
		return copyReceiptHelper(receipt, newTrip);
	}

	public void copyReceiptParallel(ReceiptRow receipt, TripRow newTrip) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		(new CopyReceiptWorker(receipt, newTrip)).execute(new Void[0]);
	}

	private boolean copyReceiptHelper(ReceiptRow receipt, TripRow newTrip) {
		File newFile = null;
		final StorageManager storageManager = mPersistenceManager.getStorageManager();
		if (receipt.hasFile()) {
			try {
				newFile = storageManager.getFile(newTrip.getDirectory(), receipt.getFileName());
				if (!storageManager.copy(receipt.getFile(), newFile, true)) {
					newFile = null; // Unset on failed copy
					return false;
				}
				else {
					if (BuildConfig.DEBUG) {
						Log.d(TAG, "Successfully created a copy of " + receipt.getFileName() + " for " + receipt.getName() + " at " + newFile.getAbsolutePath());
					}
				}
			}
			catch (IOException e) {
				if (BuildConfig.DEBUG) {
					Log.e(TAG, e.toString());
				}
				return false;
			}
		}
		if (insertReceiptSerial(newTrip, receipt, newFile) != null) { // i.e. successfully inserted
			return true;
		}
		else {
			if (newFile != null) { // roll back
				storageManager.delete(newFile);
			}
			return false;
		}
	}

	private class CopyReceiptWorker extends AsyncTask<Void, Void, Boolean> {

		private final ReceiptRow mReceipt;
		private final TripRow mTrip;

		public CopyReceiptWorker(ReceiptRow receipt, TripRow currentTrip) {
			mReceipt = receipt;
			mTrip = currentTrip;
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			return copyReceiptHelper(mReceipt, mTrip);
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (mReceiptRowListener != null) {
				if (result) {
					mReceiptRowListener.onReceiptCopySuccess(mTrip);
				}
				else {
					mReceiptRowListener.onReceiptCopyFailure();
				}
			}
		}

	}

	public boolean moveReceiptSerial(ReceiptRow receipt, TripRow currentTrip, TripRow newTrip) {
		return moveReceiptHelper(receipt, currentTrip, newTrip);
	}

	public void moveReceiptParallel(ReceiptRow receipt, TripRow currentTrip, TripRow newTrip) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		(new MoveReceiptWorker(receipt, currentTrip, newTrip)).execute(new Void[0]);
	}

	private boolean moveReceiptHelper(ReceiptRow receipt, TripRow currentTrip, TripRow newTrip) {
		if (copyReceiptSerial(receipt, newTrip)) {
			if (deleteReceiptSerial(receipt, currentTrip)) {
				return true;
			}
			else {
				// TODO: Undo Copy here
				return false;
			}
		}
		else {
			return false;
		}
	}

	private class MoveReceiptWorker extends AsyncTask<Void, Void, Boolean> {

		private final ReceiptRow mReceipt;
		private final TripRow mCurrentTrip, mNewTrip;

		public MoveReceiptWorker(ReceiptRow receipt, TripRow currentTrip, TripRow newTrip) {
			mReceipt = receipt;
			mCurrentTrip = currentTrip;
			mNewTrip = newTrip;
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			return moveReceiptHelper(mReceipt, mCurrentTrip, mNewTrip);
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (mReceiptRowListener != null) {
				if (result) {
					mReceiptRowListener.onReceiptMoveSuccess(mNewTrip);
				}
				else {
					mReceiptRowListener.onReceiptMoveFailure();
				}
			}
		}

	}

	public boolean deleteReceiptSerial(ReceiptRow receipt, TripRow currentTrip) {
		return deleteReceiptHelper(receipt, currentTrip);
	}

	public void deleteReceiptParallel(ReceiptRow receipt, TripRow currentTrip) {
		if (mReceiptRowListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowListener was registered.");
			}
		}
		(new DeleteReceiptWorker(receipt, currentTrip)).execute(new Void[0]);
	}

	private boolean deleteReceiptHelper(ReceiptRow receipt, TripRow currentTrip) {
		boolean success = false;
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			success = (db.delete(ReceiptsTable.TABLE_NAME, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(receipt.getId()) }) > 0);
		}
		if (success) {
			if (receipt.hasFile()) {
				success = success & mPersistenceManager.getStorageManager().delete(receipt.getFile());
			}
			this.updateTripPrice(currentTrip);
			synchronized (mReceiptCacheLock) {
				mNextReceiptAutoIncrementId = -1;
				mReceiptCache.remove(currentTrip);
			}
		}
		return success;
	}

	private class DeleteReceiptWorker extends AsyncTask<Void, Void, Boolean> {

		private final ReceiptRow mReceipt;
		private final TripRow mTrip;

		public DeleteReceiptWorker(ReceiptRow receipt, TripRow currentTrip) {
			mReceipt = receipt;
			mTrip = currentTrip;
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			return deleteReceiptHelper(mReceipt, mTrip);
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (mReceiptRowListener != null) {
				if (result) {
					mReceiptRowListener.onReceiptDeleteSuccess(mReceipt);
				}
				else {
					mReceiptRowListener.onReceiptDeleteFailure();
				}
			}
		}

	}

	public final boolean moveReceiptUp(final TripRow trip, final ReceiptRow receipt) {
		List<ReceiptRow> receipts = getReceiptsSerial(trip);
		int index = 0;
		final int size = receipts.size();
		for (int i = 0; i < size; i++) {
			if (receipt.getId() == receipts.get(i).getId()) {
				index = i - 1;
				break;
			}
		}
		if (index < 0) {
			return false;
		}
		ReceiptRow up = receipts.get(index);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				ContentValues upValues = new ContentValues(1);
				ContentValues downValues = new ContentValues(1);
				upValues.put(ReceiptsTable.COLUMN_DATE, receipt.getDate().getTime());
				if (receipt.getDate().getTime() != up.getDate().getTime()) {
					downValues.put(ReceiptsTable.COLUMN_DATE, up.getDate().getTime());
				}
				else {
					downValues.put(ReceiptsTable.COLUMN_DATE, up.getDate().getTime() + 1L);
				}
				if ((db.update(ReceiptsTable.TABLE_NAME, upValues, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(up.getId()) }) == 0)) {
					return false;
				}
				if ((db.update(ReceiptsTable.TABLE_NAME, downValues, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(receipt.getId()) }) == 0)) {
					return false;
				}
				mReceiptCache.remove(trip);
				return true;
			}
			catch (SQLException e) {
				return false;
			}
		}
	}

	public final boolean moveReceiptDown(final TripRow trip, final ReceiptRow receipt) {
		List<ReceiptRow> receipts = getReceiptsSerial(trip);
		final int size = receipts.size();
		int index = size - 1;
		for (int i = 0; i < receipts.size(); i++) {
			if (receipt.getId() == receipts.get(i).getId()) {
				index = i + 1;
				break;
			}
		}
		if (index > (size - 1)) {
			return false;
		}
		ReceiptRow down = receipts.get(index);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				ContentValues upValues = new ContentValues(1);
				ContentValues downValues = new ContentValues(1);
				if (receipt.getDate().getTime() != down.getDate().getTime()) {
					upValues.put(ReceiptsTable.COLUMN_DATE, down.getDate().getTime());
				}
				else {
					upValues.put(ReceiptsTable.COLUMN_DATE, down.getDate().getTime() - 1L);
				}
				downValues.put(ReceiptsTable.COLUMN_DATE, receipt.getDate().getTime());
				if ((db.update(ReceiptsTable.TABLE_NAME, upValues, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(receipt.getId()) }) == 0)) {
					return false;
				}
				if ((db.update(ReceiptsTable.TABLE_NAME, downValues, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(down.getId()) }) == 0)) {
					return false;
				}
				mReceiptCache.remove(trip);
				return true;
			}
			catch (SQLException e) {
				return false;
			}
		}
	}

	public int getNextReceiptAutoIncremenetIdSerial() {
		return getNextReceiptAutoIncremenetIdHelper();
	}

	private int getNextReceiptAutoIncremenetIdHelper() {
		if (mNextReceiptAutoIncrementId > 0) {
			return mNextReceiptAutoIncrementId;
		}
		SQLiteDatabase db = getReadableDatabase();
		Cursor c = null;
		try {
			synchronized (mDatabaseLock) {

				c = db.rawQuery("SELECT seq FROM SQLITE_SEQUENCE WHERE name=?", new String[] { ReceiptsTable.TABLE_NAME });
				if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
					mNextReceiptAutoIncrementId = c.getInt(0) + 1;
				}
				else {
					mNextReceiptAutoIncrementId = 1;
				}
				return mNextReceiptAutoIncrementId;
			}
		}
		finally {
			if (c != null) {
				c.close();
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// ReceiptRow Graph Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public void registerReceiptRowGraphListener(ReceiptRowGraphListener listener) {
		mReceiptRowGraphListener = listener;
	}

	public void unregisterReceiptRowGraphListener() {
		mReceiptRowGraphListener = null;
	}

	/**
	 * This basic internal delegate is used to prevent code reptition since all the queries will be the same. We only
	 * have to swap out column names and how the receipts are built
	 */
	private interface GraphProcessorDelegate {
		public String getXAxisColumn(); // refers to the field without aggregation

		public String getSumColumn(); // refers to the SUM() field in SQL

		public ReceiptRow getReceipt(String xaxis, String sum);
	}

	private List<ReceiptRow> getGraphColumnsSerial(TripRow trip, GraphProcessorDelegate delegate) {
		return getGraphColumnsHelper(trip, delegate);
	}

	private void getGraphColumnsParallel(TripRow trip, GraphProcessorDelegate delegate) {
		if (mReceiptRowGraphListener == null) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "No ReceiptRowGraphListener was registered.");
			}
		}
		(new GetGraphColumnsWorker(delegate)).execute(trip);
	}

	private class GetGraphColumnsWorker extends AsyncTask<TripRow, Void, List<ReceiptRow>> {

		private final GraphProcessorDelegate mDelegate;

		public GetGraphColumnsWorker(GraphProcessorDelegate delegate) {
			mDelegate = delegate;
		}

		@Override
		protected List<ReceiptRow> doInBackground(TripRow... params) {
			if (params == null || params.length == 0) {
				return new ArrayList<ReceiptRow>();
			}
			TripRow trip = params[0];
			return getGraphColumnsHelper(trip, mDelegate);
		}

		@Override
		protected void onPostExecute(List<ReceiptRow> result) {
			if (mReceiptRowGraphListener != null) {
				mReceiptRowGraphListener.onGraphQuerySuccess(result);
			}
		}

	}

	private final List<ReceiptRow> getGraphColumnsHelper(TripRow trip, GraphProcessorDelegate delegate) {
		List<ReceiptRow> receipts;
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				final String[] columns = new String[] { delegate.getXAxisColumn(), "SUM(" + delegate.getSumColumn() + ") AS " + delegate.getSumColumn() };
				c = db.query(ReceiptsTable.TABLE_NAME, columns, ReceiptsTable.COLUMN_PARENT + "= ?", new String[] { trip.getName() }, delegate.getXAxisColumn(), null, null);
				if (c != null && c.moveToFirst()) {
					receipts = new ArrayList<ReceiptRow>(c.getCount());
					final int xIndex = c.getColumnIndex(delegate.getXAxisColumn());
					final int sumIndex = c.getColumnIndex(delegate.getSumColumn());
					do {
						final String xaxis = c.getString(xIndex);
						final String sum = c.getString(sumIndex);
						receipts.add(delegate.getReceipt(xaxis, sum));
					}
					while (c.moveToNext());
				}
				else {
					receipts = new ArrayList<ReceiptRow>();
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
		return receipts;
	}

	private static class CostPerCategoryDelegate implements GraphProcessorDelegate {
		@Override
		public String getXAxisColumn() {
			return ReceiptsTable.COLUMN_CATEGORY;
		}

		@Override
		public String getSumColumn() {
			return ReceiptsTable.COLUMN_PRICE;
		}

		@Override
		public ReceiptRow getReceipt(String xaxis, String sum) {
			return (new ReceiptRow.Builder(-1)).setCategory(xaxis).setPrice(sum).build();
		}
	}

	public List<ReceiptRow> getCostPerCategorySerial(final TripRow trip) {
		return getGraphColumnsSerial(trip, new CostPerCategoryDelegate());
	}

	public void getCostPerCategoryParallel(final TripRow trip) {
		getGraphColumnsParallel(trip, new CostPerCategoryDelegate());
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// Categories Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public final ArrayList<CharSequence> getCategoriesList() {
		if (mCategoryList != null) {
			return mCategoryList;
		}
		if (mCategories == null) {
			buildCategories();
		}
		mCategoryList = new ArrayList<CharSequence>(mCategories.keySet());
		Collections.sort(mCategoryList, _charSequenceComparator);
		return mCategoryList;
	}

	private final CharSequenceComparator _charSequenceComparator = new CharSequenceComparator();

	private final class CharSequenceComparator implements Comparator<CharSequence> {
		@Override
		public int compare(CharSequence str1, CharSequence str2) {
			return str1.toString().compareToIgnoreCase(str2.toString());
		}
	}

	public final String getCategoryCode(CharSequence categoryName) {
		if (mCategories == null || mCategories.size() == 0) {
			buildCategories();
		}
		return mCategories.get(categoryName);
	}

	private final void buildCategories() {
		mCategories = new HashMap<String, String>();
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(CategoriesTable.TABLE_NAME, null, null, null, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int nameIndex = c.getColumnIndex(CategoriesTable.COLUMN_NAME);
					final int codeIndex = c.getColumnIndex(CategoriesTable.COLUMN_CODE);
					do {
						final String name = c.getString(nameIndex);
						final String code = c.getString(codeIndex);
						mCategories.put(name, code);
					}
					while (c.moveToNext());
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public final ArrayList<CharSequence> getCurrenciesList() {
		if (mCurrencyList != null) {
			return mCurrencyList;
		}
		mCurrencyList = new ArrayList<CharSequence>();
		mCurrencyList.addAll(WBCurrency.getIso4217CurrencyCodes());
		mCurrencyList.addAll(WBCurrency.getNonIso4217CurrencyCodes());
		Collections.sort(mCurrencyList, _charSequenceComparator);
		return mCurrencyList;
	}

	public final boolean insertCategory(final String name, final String code) throws SQLException {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			ContentValues values = new ContentValues(2);
			values.put(CategoriesTable.COLUMN_NAME, name);
			values.put(CategoriesTable.COLUMN_CODE, code);
			if (db.insertOrThrow(CategoriesTable.TABLE_NAME, null, values) == -1) {
				return false;
			}
			else {
				mCategories.put(name, code);
				mCategoryList.add(name);
				Collections.sort(mCategoryList, _charSequenceComparator);
				return true;
			}
		}
	}

	@SuppressWarnings("resource")
	public final boolean insertCategoryNoCache(final String name, final String code) throws SQLException {
		final SQLiteDatabase db = (_initDB != null) ? _initDB : this.getReadableDatabase();
		ContentValues values = new ContentValues(2);
		values.put(CategoriesTable.COLUMN_NAME, name);
		values.put(CategoriesTable.COLUMN_CODE, code);
		if (db.insertOrThrow(CategoriesTable.TABLE_NAME, null, values) == -1) {
			return false;
		}
		else {
			return true;
		}
	}

	public final boolean updateCategory(final String oldName, final String newName, final String newCode) {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			ContentValues values = new ContentValues(2);
			values.put(CategoriesTable.COLUMN_NAME, newName);
			values.put(CategoriesTable.COLUMN_CODE, newCode);
			if (db.update(CategoriesTable.TABLE_NAME, values, CategoriesTable.COLUMN_NAME + " = ?", new String[] { oldName }) == 0) {
				return false;
			}
			else {
				mCategories.remove(oldName);
				mCategoryList.remove(oldName);
				mCategories.put(newName, newCode);
				mCategoryList.add(newName);
				Collections.sort(mCategoryList, _charSequenceComparator);
				return true;
			}

		}
	}

	public final boolean deleteCategory(final String name) {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			final boolean success = (db.delete(CategoriesTable.TABLE_NAME, CategoriesTable.COLUMN_NAME + " = ?", new String[] { name }) > 0);
			if (success) {
				mCategories.remove(name);
				mCategoryList.remove(name);
			}
			return success;
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// CSV Columns Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public final CSVColumns getCSVColumns() {
		if (mCSVColumns != null) {
			return mCSVColumns;
		}
		mCSVColumns = new CSVColumns(mContext, this, mFlex, mPersistenceManager);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(CSVTable.TABLE_NAME, null, null, null, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int idxIndex = c.getColumnIndex(CSVTable.COLUMN_ID);
					final int typeIndex = c.getColumnIndex(CSVTable.COLUMN_TYPE);
					do {
						final int index = c.getInt(idxIndex);
						final String type = c.getString(typeIndex);
						mCSVColumns.add(index, type);
					}
					while (c.moveToNext());
				}
				return mCSVColumns;
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public final boolean insertCSVColumn() {
		ContentValues values = new ContentValues(1);
		values.put(CSVTable.COLUMN_TYPE, CSVColumns.BLANK(mFlex));
		if (mCSVColumns == null) {
			getCSVColumns();
		}
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getWritableDatabase();
				if (db.insertOrThrow(CSVTable.TABLE_NAME, null, values) == -1) {
					return false;
				}
				else {
					c = db.rawQuery("SELECT last_insert_rowid()", null);
					if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
						final int idx = c.getInt(0);
						mCSVColumns.add(idx, CSVColumns.BLANK(mFlex));
					}
					else {
						return false;
					}
					return true;
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public final boolean insertCSVColumnNoCache(String column) {
		ContentValues values = new ContentValues(1);
		values.put(CSVTable.COLUMN_TYPE, column);
		if (_initDB != null) {
			// TODO: Determine if database lock should be used here
			if (_initDB.insertOrThrow(CSVTable.TABLE_NAME, null, values) == -1) {
				return false;
			}
			else {
				return true;
			}
		}
		else {
			synchronized (mDatabaseLock) {
				SQLiteDatabase db = null;
				db = this.getWritableDatabase();
				if (db.insertOrThrow(CSVTable.TABLE_NAME, null, values) == -1) {
					return false;
				}
				else {
					return true;
				}
			}
		}
	}

	public final boolean deleteCSVColumn() {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			int idx = mCSVColumns.removeLast();
			if (idx < 0) {
				return false;
			}
			return db.delete(CSVTable.TABLE_NAME, CSVTable.COLUMN_ID + " = ?", new String[] { Integer.toString(idx) }) > 0;
		}
	}

	public final boolean updateCSVColumn(int arrayListIndex, int optionIndex) { // Note index here refers to the actual
																				// index and not the ID
		Column currentColumn = mCSVColumns.get(arrayListIndex);
		if (currentColumn.getColumnType().equals(mCSVColumns.getSpinnerOptionAt(optionIndex))) {
			// Don't bother updating, since we've already set this column type
			return true;
		}
		Column column = mCSVColumns.update(arrayListIndex, optionIndex);
		ContentValues values = new ContentValues(1);
		values.put(CSVTable.COLUMN_TYPE, column.getColumnType());
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				if (db.update(CSVTable.TABLE_NAME, values, CSVTable.COLUMN_ID + " = ?", new String[] { Integer.toString(column.getIndex()) }) == 0) {
					return false;
				}
				else {
					return true;
				}
			}
			catch (SQLException e) {
				return false;
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// PDF Columns Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public final PDFColumns getPDFColumns() {
		if (mPDFColumns != null) {
			return mPDFColumns;
		}
		mPDFColumns = new PDFColumns(mContext, this, mFlex, mPersistenceManager);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(PDFTable.TABLE_NAME, null, null, null, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int idxIndex = c.getColumnIndex(PDFTable.COLUMN_ID);
					final int typeIndex = c.getColumnIndex(PDFTable.COLUMN_TYPE);
					do {
						final int index = c.getInt(idxIndex);
						final String type = c.getString(typeIndex);
						mPDFColumns.add(index, type);
					}
					while (c.moveToNext());
				}
				return mPDFColumns;
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public final boolean insertPDFColumn() {
		ContentValues values = new ContentValues(1);
		values.put(PDFTable.COLUMN_TYPE, PDFColumns.BLANK(mFlex));
		if (mPDFColumns == null) {
			getPDFColumns();
		}
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getWritableDatabase();
				if (db.insertOrThrow(PDFTable.TABLE_NAME, null, values) == -1) {
					return false;
				}
				else {
					c = db.rawQuery("SELECT last_insert_rowid()", null);
					if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
						final int idx = c.getInt(0);
						mPDFColumns.add(idx, PDFColumns.BLANK(mFlex));
					}
					else {
						return false;
					}
					return true;
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	public final boolean insertPDFColumnNoCache(String column) {
		ContentValues values = new ContentValues(1);
		values.put(PDFTable.COLUMN_TYPE, column);
		if (_initDB != null) {
			// TODO: Determine if database lock should be used here
			if (_initDB.insertOrThrow(PDFTable.TABLE_NAME, null, values) == -1) {
				return false;
			}
			else {
				return true;
			}
		}
		else {
			synchronized (mDatabaseLock) {
				SQLiteDatabase db = null;
				db = this.getWritableDatabase();
				if (db.insertOrThrow(PDFTable.TABLE_NAME, null, values) == -1) {
					return false;
				}
				else {
					return true;
				}
			}
		}
	}

	public final boolean deletePDFColumn() {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			int idx = mPDFColumns.removeLast();
			if (idx < 0) {
				return false;
			}
			return db.delete(PDFTable.TABLE_NAME, PDFTable.COLUMN_ID + " = ?", new String[] { Integer.toString(idx) }) > 0;
		}
	}

	public final boolean updatePDFColumn(int arrayListIndex, int optionIndex) { // Note index here refers to the actual
																				// index and not the ID
		Column currentColumn = mPDFColumns.get(arrayListIndex);
		if (currentColumn.getColumnType().equals(mPDFColumns.getSpinnerOptionAt(optionIndex))) {
			// Don't bother updating, since we've already set this column type
			return true;
		}
		Column column = mPDFColumns.update(arrayListIndex, optionIndex);
		ContentValues values = new ContentValues(1);
		values.put(PDFTable.COLUMN_TYPE, column.getColumnType());
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				if (db.update(PDFTable.TABLE_NAME, values, PDFTable.COLUMN_ID + " = ?", new String[] { Integer.toString(column.getIndex()) }) == 0) {
					return false;
				}
				else {
					return true;
				}
			}
			catch (SQLException e) {
				return false;
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// PaymentMethod Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	/**
	 * Fetches the list of all {@link PaymentMethod}. This is done on the calling thread.
	 * 
	 * @return the {@link List} of {@link PaymentMethod} objects that we've saved
	 */
	public final List<PaymentMethod> getPaymentMethods() {
		if (mPaymentMethods != null) {
			return mPaymentMethods;
		}
		mPaymentMethods = new ArrayList<PaymentMethod>();
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getReadableDatabase();
				c = db.query(PaymentMethodsTable.TABLE_NAME, null, null, null, null, null, null);
				if (c != null && c.moveToFirst()) {
					final int idIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_ID);
					final int methodIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_METHOD);
					do {
						final int id = c.getInt(idIndex);
						final String method = c.getString(methodIndex);
						final PaymentMethod.Builder builder = new PaymentMethod.Builder();
						mPaymentMethods.add(builder.setId(id).setMethod(method).build());
					}
					while (c.moveToNext());
				}
				return mPaymentMethods;
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	/**
	 * Attempts to fetch a payment method for a given primary key id
	 * 
	 * @param id
	 *            - the id of the desired {@link PaymentMethod}
	 * @return a {@link PaymentMethod} if the id matches or {@code null} if none is found
	 */
	public final PaymentMethod findPaymentMethodById(final int id) {
		final List<PaymentMethod> methodsSnapshot = new ArrayList<PaymentMethod>(getPaymentMethods());
		final int size = methodsSnapshot.size();
		for (int i = 0; i < size; i++) {
			final PaymentMethod method = methodsSnapshot.get(i);
			if (method.getId() == id) {
				return method;
			}
		}
		return null;
	}

	/**
	 * Inserts a new {@link PaymentMethod} into our database. This method also automatically updates the underlying list
	 * that is returned from {@link #getPaymentMethods()}. This is done on the calling thread.
	 * 
	 * @param method
	 *            - a {@link String} representing the current method
	 * @return a new {@link PaymentMethod} if it was successfully inserted, {@code null} if not
	 */
	public final PaymentMethod insertPaymentMethod(final String method) {
		ContentValues values = new ContentValues(1);
		values.put(PaymentMethodsTable.COLUMN_METHOD, method);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			Cursor c = null;
			try {
				db = this.getWritableDatabase();
				if (db.insertOrThrow(PaymentMethodsTable.TABLE_NAME, null, values) == -1) {
					return null;
				}
				else {
					final PaymentMethod.Builder builder = new PaymentMethod.Builder();
					final PaymentMethod paymentMethod;
					c = db.rawQuery("SELECT last_insert_rowid()", null);
					if (c != null && c.moveToFirst() && c.getColumnCount() > 0) {
						final int id = c.getInt(0);
						paymentMethod = builder.setId(id).setMethod(method).build();
					}
					else {
						paymentMethod = builder.setId(-1).setMethod(method).build();
					}
					if (mPaymentMethods != null) {
						mPaymentMethods.add(paymentMethod);
					}
					return paymentMethod;
				}
			}
			finally { // Close the cursor and db to avoid memory leaks
				if (c != null) {
					c.close();
				}
			}
		}
	}

	/**
	 * Inserts a new {@link PaymentMethod} into our database. This method does not update the underlying list that is
	 * returned via {{@link #getPaymentMethods()}
	 * 
	 * @param method
	 *            - a {@link String} representing the current method
	 * @return {@code true} if it was properly inserted. {@code false} if not
	 */
	public final boolean insertPaymentMethodNoCache(final String method) {
		ContentValues values = new ContentValues(1);
		values.put(PaymentMethodsTable.COLUMN_METHOD, method);
		if (_initDB != null) {
			if (_initDB.insertOrThrow(PaymentMethodsTable.TABLE_NAME, null, values) == -1) {
				return false;
			}
			else {
				return true;
			}
		}
		else {
			synchronized (mDatabaseLock) {
				SQLiteDatabase db = null;
				db = this.getWritableDatabase();
				if (db.insertOrThrow(PaymentMethodsTable.TABLE_NAME, null, values) == -1) {
					return false;
				}
				else {
					return true;
				}
			}
		}
	}

	/**
	 * Updates a Payment method with a new method type. This method also automatically updates the underlying list that
	 * is returned from {@link #getPaymentMethods()}. This is done on the calling thread.
	 * 
	 * @param oldPaymentMethod
	 *            - the old method to update
	 * @param newMethod
	 *            - the new string to use as the method
	 * @return the new {@link PaymentMethod}
	 */
	public final PaymentMethod updatePaymentMethod(final PaymentMethod oldPaymentMethod, final String newMethod) {
		if (oldPaymentMethod == null) {
			Log.e(TAG, "The oldPaymentMethod is null. No update can be performed");
			return null;
		}
		if (oldPaymentMethod.getMethod() == null && newMethod == null) {
			return oldPaymentMethod;
		}
		else if (newMethod != null && newMethod.equals(oldPaymentMethod.getMethod())) {
			return oldPaymentMethod;
		}

		ContentValues values = new ContentValues(1);
		values.put(PaymentMethodsTable.COLUMN_METHOD, newMethod);
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			try {
				db = this.getWritableDatabase();
				if (db.update(PaymentMethodsTable.TABLE_NAME, values, PaymentMethodsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(oldPaymentMethod.getId()) }) > 0) {
					final PaymentMethod.Builder builder = new PaymentMethod.Builder();
					final PaymentMethod upddatePaymentMethod = builder.setId(oldPaymentMethod.getId()).setMethod(newMethod).build();
					if (mPaymentMethods != null) {
						final int oldListIndex = mPaymentMethods.indexOf(oldPaymentMethod);
						if (oldListIndex >= 0) {
							mPaymentMethods.remove(oldPaymentMethod);
							mPaymentMethods.add(oldListIndex, upddatePaymentMethod);
						}
						else {
							mPaymentMethods.add(upddatePaymentMethod);
						}
					}
					return upddatePaymentMethod;
				}
				else {
					return null;
				}
			}
			catch (SQLException e) {
				return null;
			}
		}
	}

	/**
	 * Deletes a {@link PaymentMethod} from our database. This method also automatically updates the underlying list
	 * that is returned from {@link #getPaymentMethods()}. This is done on the calling thread.
	 * 
	 * @param paymentMethod
	 *            - the {@link PaymentMethod} to delete
	 * @return {@code true} if is was successfully remove. {@code false} otherwise
	 */
	public final boolean deletePaymenthMethod(final PaymentMethod paymentMethod) {
		synchronized (mDatabaseLock) {
			SQLiteDatabase db = null;
			db = this.getWritableDatabase();
			if (db.delete(PaymentMethodsTable.TABLE_NAME, PaymentMethodsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(paymentMethod.getId()) }) > 0) {
				if (mPaymentMethods != null) {
					mPaymentMethods.remove(paymentMethod);
				}
				return true;
			}
			else {
				return false;
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// Utilities
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public final synchronized boolean merge(String dbPath, String packageName, boolean overwrite) {
		mAreTripsValid = false;
		mReceiptCache.clear();
		synchronized (mDatabaseLock) {
			SQLiteDatabase importDB = null, currDB = null;
			Cursor c = null, countCursor = null;
			try {
				if (dbPath == null) {
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Null database file");
					return false;
				}
				currDB = this.getWritableDatabase();
				importDB = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE);
				// Merge Trips
				try {
					if (BuildConfig.DEBUG) {
						Log.d(TAG, "Merging Trips");
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Trips");
					c = importDB.query(TripsTable.TABLE_NAME, null, null, null, null, null, TripsTable.COLUMN_TO + " DESC");
					if (c != null && c.moveToFirst()) {
						final int nameIndex = c.getColumnIndex(TripsTable.COLUMN_NAME);
						final int fromIndex = c.getColumnIndex(TripsTable.COLUMN_FROM);
						final int fromTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_FROM_TIMEZONE);
						final int toIndex = c.getColumnIndex(TripsTable.COLUMN_TO);
						final int toTimeZoneIndex = c.getColumnIndex(TripsTable.COLUMN_TO_TIMEZONE);
						// final int priceIndex = c.getColumnIndex(TripsTable.COLUMN_PRICE);
						final int mileageIndex = c.getColumnIndex(TripsTable.COLUMN_MILEAGE);
						do {
							String name = getString(c, nameIndex, "");
							if (name.contains("wb.receipts")) { // Backwards compatibility stuff
								if (packageName.equalsIgnoreCase("wb.receipts")) {
									name = name.replace("wb.receiptspro/", "wb.receipts/");
								}
								else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
									name = name.replace("wb.receipts/", "wb.receiptspro/");
								}
								File f = new File(name);
								name = f.getName();
							}
							final long from = getLong(c, fromIndex, 0L);
							final long to = getLong(c, toIndex, 0L);
							final int mileage = getInt(c, mileageIndex, 0);
							ContentValues values = new ContentValues(7);
							values.put(TripsTable.COLUMN_NAME, name);
							values.put(TripsTable.COLUMN_FROM, from);
							values.put(TripsTable.COLUMN_TO, to);
							values.put(TripsTable.COLUMN_MILEAGE, mileage);
							if (fromTimeZoneIndex > 0) {
								final String fromTimeZome = c.getString(fromTimeZoneIndex);
								values.put(TripsTable.COLUMN_FROM_TIMEZONE, fromTimeZome);
							}
							if (toTimeZoneIndex > 0) {
								final String toTimeZome = c.getString(toTimeZoneIndex);
								values.put(TripsTable.COLUMN_TO_TIMEZONE, toTimeZome);
							}
							if (overwrite) {
								currDB.insertWithOnConflict(TripsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
							}
							else {
								currDB.insertWithOnConflict(TripsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
							}
						}
						while (c.moveToNext());
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a1]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}

				// Merge Receipts
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Merging Receipts");
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Receipts");
				try {
					final String queryCount = "SELECT COUNT(*), " + ReceiptsTable.COLUMN_ID + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_PATH + "=? AND " + ReceiptsTable.COLUMN_NAME + "=? AND " + ReceiptsTable.COLUMN_DATE + "=?";
					c = importDB.query(ReceiptsTable.TABLE_NAME, null, null, null, null, null, null);
					if (c != null && c.moveToFirst()) {
						final int pathIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PATH);
						final int nameIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NAME);
						final int parentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PARENT);
						final int categoryIndex = c.getColumnIndex(ReceiptsTable.COLUMN_CATEGORY);
						final int priceIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PRICE);
						final int dateIndex = c.getColumnIndex(ReceiptsTable.COLUMN_DATE);
						final int commentIndex = c.getColumnIndex(ReceiptsTable.COLUMN_COMMENT);
						final int expenseableIndex = c.getColumnIndex(ReceiptsTable.COLUMN_EXPENSEABLE);
						final int currencyIndex = c.getColumnIndex(ReceiptsTable.COLUMN_ISO4217);
						final int fullpageIndex = c.getColumnIndex(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE);
						final int extra_edittext_1_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1);
						final int extra_edittext_2_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2);
						final int extra_edittext_3_Index = c.getColumnIndex(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3);
						final int taxIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TAX);
						final int timeZoneIndex = c.getColumnIndex(ReceiptsTable.COLUMN_TIMEZONE);
						final int paymentMethodIndex = c.getColumnIndex(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID);
						do {
							final String oldPath = getString(c, pathIndex, "");
							String newPath = new String(oldPath);
							if (newPath.contains("wb.receipts")) { // Backwards compatibility stuff
								if (packageName.equalsIgnoreCase("wb.receipts")) {
									newPath = oldPath.replace("wb.receiptspro/", "wb.receipts/");
								}
								else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
									newPath = oldPath.replace("wb.receipts/", "wb.receiptspro/");
								}
								File f = new File(newPath);
								newPath = f.getName();
							}
							final String name = getString(c, nameIndex, "");
							final String oldParent = getString(c, parentIndex, "");
							String newParent = new String(oldParent);
							if (newParent.contains("wb.receipts")) { // Backwards compatibility stuff
								if (packageName.equalsIgnoreCase("wb.receipts")) {
									newParent = oldParent.replace("wb.receiptspro/", "wb.receipts/");
								}
								else if (packageName.equalsIgnoreCase("wb.receiptspro")) {
									newParent = oldParent.replace("wb.receipts/", "wb.receiptspro/");
								}
								File f = new File(newParent);
								newParent = f.getName();
							}
							final String category = getString(c, categoryIndex, "");
							final String price = getString(c, priceIndex, "");
							final long date = getLong(c, dateIndex, 0L);
							final String comment = getString(c, commentIndex, "");
							final boolean expensable = getBoolean(c, expenseableIndex, true);
							final String currency = getString(c, currencyIndex, mPersistenceManager.getPreferences().getDefaultCurreny());
							final boolean fullpage = getBoolean(c, fullpageIndex, false);
							final String extra_edittext_1 = getString(c, extra_edittext_1_Index, null);
							final String extra_edittext_2 = getString(c, extra_edittext_2_Index, null);
							final String extra_edittext_3 = getString(c, extra_edittext_3_Index, null);
							final String tax = getString(c, taxIndex, "0");
							final int paymentMethod = getInt(c, paymentMethodIndex, 0);
							try {
								countCursor = currDB.rawQuery(queryCount, new String[] { newPath, name, Long.toString(date) });
								if (countCursor != null && countCursor.moveToFirst()) {
									int count = countCursor.getInt(0);
									int updateID = countCursor.getInt(1);
									final ContentValues values = new ContentValues(14);
									values.put(ReceiptsTable.COLUMN_PATH, newPath);
									values.put(ReceiptsTable.COLUMN_NAME, name);
									values.put(ReceiptsTable.COLUMN_PARENT, newParent);
									values.put(ReceiptsTable.COLUMN_CATEGORY, category);
									values.put(ReceiptsTable.COLUMN_PRICE, price);
									values.put(ReceiptsTable.COLUMN_DATE, date);
									values.put(ReceiptsTable.COLUMN_COMMENT, comment);
									values.put(ReceiptsTable.COLUMN_EXPENSEABLE, expensable);
									values.put(ReceiptsTable.COLUMN_ISO4217, currency);
									values.put(ReceiptsTable.COLUMN_NOTFULLPAGEIMAGE, fullpage);
									values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_1, extra_edittext_1);
									values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_2, extra_edittext_2);
									values.put(ReceiptsTable.COLUMN_EXTRA_EDITTEXT_3, extra_edittext_3);
									values.put(ReceiptsTable.COLUMN_TAX, tax);
									if (timeZoneIndex > 0) {
										final String timeZone = c.getString(timeZoneIndex);
										values.put(ReceiptsTable.COLUMN_TIMEZONE, timeZone);
									}
									values.put(ReceiptsTable.COLUMN_PAYMENT_METHOD_ID, paymentMethod);
									if (count > 0 && overwrite) { // Update
										currDB.update(ReceiptsTable.TABLE_NAME, values, ReceiptsTable.COLUMN_ID + " = ?", new String[] { Integer.toString(updateID) });
									}
									else { // insert
										if (overwrite) {
											currDB.insertWithOnConflict(ReceiptsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
										}
										else if (count == 0) {
											// If we're not overwriting anything, let's check that there are no entries here
											currDB.insertWithOnConflict(ReceiptsTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
										}
									}
								}
							}
							finally {
								if (countCursor != null && !countCursor.isClosed()) {
									countCursor.close();
									countCursor = null;
								}
							}
						}
						while (c.moveToNext());
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a2]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}

				// Merge Categories
				// No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
				// enough outlying data) => Always overwirte
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Merging Categories");
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging Categories");
				try {
					c = importDB.query(CategoriesTable.TABLE_NAME, null, null, null, null, null, null);
					if (c != null && c.moveToFirst()) {
						currDB.delete(CategoriesTable.TABLE_NAME, null, null); // DELETE FROM Categories
						final int nameIndex = c.getColumnIndex(CategoriesTable.COLUMN_NAME);
						final int codeIndex = c.getColumnIndex(CategoriesTable.COLUMN_CODE);
						final int breakdownIndex = c.getColumnIndex(CategoriesTable.COLUMN_BREAKDOWN);
						do {
							final String name = getString(c, nameIndex, "");
							final String code = getString(c, codeIndex, "");
							final boolean breakdown = getBoolean(c, breakdownIndex, true);
							ContentValues values = new ContentValues(3);
							values.put(CategoriesTable.COLUMN_NAME, name);
							values.put(CategoriesTable.COLUMN_CODE, code);
							values.put(CategoriesTable.COLUMN_BREAKDOWN, breakdown);
							currDB.insert(CategoriesTable.TABLE_NAME, null, values);
						}
						while (c.moveToNext());
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a3]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}

				// Merge CSV
				// No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
				// enough outlying data) => Always overwirte
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Merging CSV");
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging CSV");
				try {
					c = importDB.query(CSVTable.TABLE_NAME, null, null, null, null, null, null);
					if (c != null && c.moveToFirst()) {
						currDB.delete(CSVTable.TABLE_NAME, null, null); // DELETE * FROM CSVTable
						final int idxIndex = c.getColumnIndex(CSVTable.COLUMN_ID);
						final int typeIndex = c.getColumnIndex(CSVTable.COLUMN_TYPE);
						do {
							final int index = getInt(c, idxIndex, 0);
							final String type = getString(c, typeIndex, "");
							ContentValues values = new ContentValues(2);
							values.put(CSVTable.COLUMN_ID, index);
							values.put(CSVTable.COLUMN_TYPE, type);
							currDB.insert(CSVTable.TABLE_NAME, null, values);
						}
						while (c.moveToNext());
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a4]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}

				// Merge PDF
				// No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
				// enough outlying data) => Always overwirte
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Merging PDF");
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Merging PDF");
				try {
					c = importDB.query(PDFTable.TABLE_NAME, null, null, null, null, null, null);
					if (c != null && c.moveToFirst()) {
						currDB.delete(PDFTable.TABLE_NAME, null, null); // DELETE * FROM PDFTable
						final int idxIndex = c.getColumnIndex(PDFTable.COLUMN_ID);
						final int typeIndex = c.getColumnIndex(PDFTable.COLUMN_TYPE);
						do {
							final int index = getInt(c, idxIndex, 0);
							final String type = getString(c, typeIndex, "");
							ContentValues values = new ContentValues(2);
							values.put(PDFTable.COLUMN_ID, index);
							values.put(PDFTable.COLUMN_TYPE, type);
							currDB.insert(PDFTable.TABLE_NAME, null, values);
						}
						while (c.moveToNext());
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a5]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}

				// Merge Payment methods
				// No clean way to merge (since auto-increment is not guaranteed to have any order and there isn't
				// enough outlying data) => Always overwirte
				if (BuildConfig.DEBUG) {
					Log.d(TAG, "Merging Payment Methods");
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Payment Methods");
				try {
					c = importDB.query(PaymentMethodsTable.TABLE_NAME, null, null, null, null, null, null);
					if (c != null && c.moveToFirst()) {
						currDB.delete(PaymentMethodsTable.TABLE_NAME, null, null); // DELETE * FROM PaymentMethodsTable
						final int idxIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_ID);
						final int typeIndex = c.getColumnIndex(PaymentMethodsTable.COLUMN_METHOD);
						do {
							final int index = getInt(c, idxIndex, 0);
							final String type = getString(c, typeIndex, "");
							ContentValues values = new ContentValues(2);
							values.put(PaymentMethodsTable.COLUMN_ID, index);
							values.put(PaymentMethodsTable.COLUMN_METHOD, type);
							currDB.insert(PaymentMethodsTable.TABLE_NAME, null, values);
						}
						while (c.moveToNext());
					}
					else {
						return false;
					}
				}
				catch (SQLiteException e) {
					if (BuildConfig.DEBUG) {
						Log.e(TAG, e.toString(), e); // Occurs if Table does not exist
					}
					mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught sql exception during import at [a6]: " + Utils.getStackTrace(e));
				}
				finally {
					if (c != null && !c.isClosed()) {
						c.close();
						c = null;
					}
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Success");
				return true;
			}
			catch (Exception e) {
				if (BuildConfig.DEBUG) {
					Log.e(TAG, e.toString());
				}
				mPersistenceManager.getStorageManager().appendTo(ImportTask.LOG_FILE, "Caught fatal db exception during import at [a7]: " + Utils.getStackTrace(e));
				return false;
			}
			finally {
				if (c != null && !c.isClosed()) {
					c.close();
				}
				if (countCursor != null && !countCursor.isClosed()) {
					countCursor.close();
				}
				if (importDB != null) {
					importDB.close();
				}
			}
		}
	}

	private boolean getBoolean(Cursor cursor, int index, boolean defaultValue) {
		if (index >= 0) {
			return (cursor.getInt(index) > 0);
		}
		else {
			return defaultValue;
		}
	}

	private int getInt(Cursor cursor, int index, int defaultValue) {
		if (index >= 0) {
			return cursor.getInt(index);
		}
		else {
			return defaultValue;
		}
	}

	private long getLong(Cursor cursor, int index, long defaultValue) {
		if (index >= 0) {
			return cursor.getLong(index);
		}
		else {
			return defaultValue;
		}
	}

	private String getString(Cursor cursor, int index, String defaultValue) {
		if (index >= 0) {
			return cursor.getString(index);
		}
		else {
			return defaultValue;
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// AutoCompleteTextView Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public Cursor getAutoCompleteCursor(CharSequence text, CharSequence tag) {
		// TODO: Fix SQL vulnerabilities
		final SQLiteDatabase db = this.getReadableDatabase();
		String sqlQuery = "";
		if (tag == TAG_RECEIPTS_NAME) {
			sqlQuery = " SELECT DISTINCT TRIM(" + ReceiptsTable.COLUMN_NAME + ") AS _id " + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_NAME + " LIKE '%" + text + "%' " + " ORDER BY " + ReceiptsTable.COLUMN_NAME;
		}
		else if (tag == TAG_RECEIPTS_COMMENT) {
			sqlQuery = " SELECT DISTINCT TRIM(" + ReceiptsTable.COLUMN_COMMENT + ") AS _id " + " FROM " + ReceiptsTable.TABLE_NAME + " WHERE " + ReceiptsTable.COLUMN_COMMENT + " LIKE '%" + text + "%' " + " ORDER BY " + ReceiptsTable.COLUMN_COMMENT;
		}
		else if (tag == TAG_TRIPS) {
			sqlQuery = " SELECT DISTINCT TRIM(" + TripsTable.COLUMN_NAME + ") AS _id " + " FROM " + TripsTable.TABLE_NAME + " WHERE " + TripsTable.COLUMN_NAME + " LIKE '%" + text + "%' " + " ORDER BY " + TripsTable.COLUMN_NAME;
		}
		synchronized (mDatabaseLock) {
			return db.rawQuery(sqlQuery, null);
		}
	}

	@Override
	public void onItemSelected(CharSequence text, CharSequence tag) {
		// TODO: Make Async

		Cursor c = null;
		SQLiteDatabase db = null;
		final String name = text.toString();
		if (tag == TAG_RECEIPTS_NAME) {
			String category = null, price = null, tmp = null;
			// If we're not predicting, return
			if (!mPersistenceManager.getPreferences().predictCategories()) {
				// price = null;
				// category = null
			}
			else {
				synchronized (mDatabaseLock) {
					try {
						db = this.getReadableDatabase();
						c = db.query(ReceiptsTable.TABLE_NAME, new String[] { ReceiptsTable.COLUMN_CATEGORY, ReceiptsTable.COLUMN_PRICE }, ReceiptsTable.COLUMN_NAME + "= ?", new String[] { name }, null, null, ReceiptsTable.COLUMN_DATE + " DESC", "2");
						if (c != null && c.getCount() == 2) {
							if (c.moveToFirst()) {
								category = c.getString(0);
								price = c.getString(1);
								if (c.moveToNext()) {
									tmp = c.getString(0);
									if (!category.equalsIgnoreCase(tmp)) {
										category = null;
									}
									tmp = c.getString(1);
									if (!price.equalsIgnoreCase(tmp)) {
										price = null;
									}
								}
							}
						}
					}
					finally {
						if (c != null) {
							c.close();
						}
					}
				}
			}
			if (mReceiptRowListener != null) {
				mReceiptRowListener.onReceiptRowAutoCompleteQueryResult(name, price, category);
			}
		}
	}

	// //////////////////////////////////////////////////////////////////////////////////////////////////
	// Support Methods
	// //////////////////////////////////////////////////////////////////////////////////////////////////
	public void backUpDatabase(final String databasePath) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				final StorageManager storageManager = mPersistenceManager.getStorageManager();
				File sdDB = storageManager.getFile(DateUtils.getCurrentDateAsYYYY_MM_DDString() + "_" + DATABASE_NAME + ".bak");
				try {
					synchronized (mDatabaseLock) {
						storageManager.copy(new File(databasePath), sdDB, true);
					}
					if (D) {
						Log.d(TAG, "Backed up database file to: " + sdDB.getName());
					}
				}
				catch (IOException e) {
					Log.e(TAG, "Failed to back up database: " + e.toString());
				}
				catch (Exception e) {
					Log.e(TAG, "Failed to back up database: " + e.toString());
					// Avoid crashing on an exception here... Just a backup utility anyway
				}
			}
		}).start();
	}
}
