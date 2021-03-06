package co.smartreceipts.android.model;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Date;
import java.text.DecimalFormat;
import java.util.TimeZone;

import wb.android.storage.StorageManager;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import co.smartreceipts.android.R;
import co.smartreceipts.android.date.DateUtils;
import co.smartreceipts.android.persistence.DatabaseHelper;

public class ReceiptRow implements Parcelable {

	public static final String PARCEL_KEY = "co.smartreceipts.android.ReceiptRow";

	private final int mId;
	private TripRow mTrip;
	private PaymentMethod mPaymentMethod;
	private int mIndex; // Tracks the index in the list (if specified)
	private File mFile;
	private String mName, mCategory, mComment;
	private BigDecimal mPrice, mTax;
	private String mExtraEditText1, mExtraEditText2, mExtraEditText3;
	private Date mDate;
	private TimeZone mTimeZone;
	private boolean mIsExpensable, mIsFullPage, mIsSelected;
	private WBCurrency mCurrency;
	private DecimalFormat mDecimalFormat;
	private SourceEnum mSource;

	private ReceiptRow(int id) {
		mId = id;
		mIndex = -1;
		mIsSelected = false;
	}

	private ReceiptRow(Parcel in) {
		mTrip = in.readParcelable(TripRow.class.getClassLoader());
		mPaymentMethod = in.readParcelable(PaymentMethod.class.getClassLoader());
		mId = in.readInt();
		mName = in.readString();
		mCategory = in.readString();
		mComment = in.readString();
		mPrice = new BigDecimal(in.readFloat());
		mTax = new BigDecimal(in.readFloat());
		final String fileName = in.readString();
		mFile = TextUtils.isEmpty(fileName) ? null : new File(fileName);
		mDate = new Date(in.readLong());
		mCurrency = WBCurrency.getInstance(in.readString());
		mIsExpensable = (in.readByte() != 0);
		mIsFullPage = (in.readByte() != 0);
		mExtraEditText1 = in.readString();
		mExtraEditText2 = in.readString();
		mExtraEditText3 = in.readString();
		mIndex = in.readInt();
		mTimeZone = TimeZone.getTimeZone(in.readString());
		mSource = SourceEnum.Parcel;
		mIsSelected = false;
	}

	public int getId() {
		return mId;
	}

	public TripRow getTrip() {
		return mTrip;
	}

	public boolean hasTrip() {
		return mTrip != null;
	}

	public PaymentMethod getPaymentMethod() {
		return mPaymentMethod;
	}

	public boolean hasPaymentMethod() {
		return mPaymentMethod != null;
	}

	public String getName() {
		return mName;
	}

	public boolean hasFile() {
		return (mFile != null && mFile.exists());
	}

	public boolean hasImage() {
		if (mFile != null && mFile.exists()) {
			final String extension = StorageManager.getExtension(mFile);
			if (extension != null
					&& (extension.equalsIgnoreCase("jpg") || extension.equalsIgnoreCase("jpeg") || extension
							.equalsIgnoreCase("png"))) {
				return true;
			}
			else {
				return false;
			}
		}
		else {
			return false;
		}
	}

	public boolean hasPDF() {
		if (mFile != null && mFile.exists()) {
			final String extension = StorageManager.getExtension(mFile);
			if (extension != null && extension.equalsIgnoreCase("pdf")) {
				return true;
			}
			else {
				return false;
			}
		}
		else {
			return false;
		}
	}

	public String getMarkerAsString(Context context) {
		if (context == null) {
			return new String();
		}
		else if (hasImage()) {
			return context.getString(R.string.image);
		}
		else if (hasPDF()) {
			return context.getString(R.string.pdf);
		}
		else {
			return new String();
		}
	}

	public File getImage() {
		return mFile;
	}

	public File getPDF() {
		return mFile;
	}

	public File getFile() {
		return mFile;
	}

	public String getFilePath() {
		if (hasFile()) {
			return mFile.getAbsolutePath();
		}
		else {
			return "";
		}
	}

	public String getFileName() {
		if (hasFile()) {
			return mFile.getName();
		}
		else {
			return "";
		}
	}

	public String getSource() {
		return mSource.name();
	}

	public String getCategory() {
		return mCategory;
	}

	public String getComment() {
		return mComment;
	}

	public String getPrice() {
		return getDecimalFormattedPrice();
	}

	public float getPriceAsFloat() {
		if (mPrice != null) {
			return mPrice.floatValue();
		}
		else {
			return 0f;
		}
	}

	public String getDecimalFormattedPrice() {
		return getDecimalFormat().format(mPrice);
	}

	public String getCurrencyFormattedPrice() {
		if (getCurrency() != null) {
			return getCurrency().format(mPrice);
		}
		else {
			return getDecimalFormattedPrice();
		}
	}

	public boolean isPriceEmpty() {
		return TextUtils.isEmpty(getPrice());
	}

	public String getTax() {
		return getDecimalFormattedTax();
	}

	public float getTaxAsFloat() {
		return mTax.floatValue();
	}

	public String getDecimalFormattedTax() {
		return getDecimalFormat().format(mTax);
	}

	public String getCurrencyFormattedTax() {
		if (getCurrency() != null) {
			return getCurrency().format(mTax);
		}
		else {
			return getDecimalFormattedTax();
		}
	}

	public String getExtraEditText1() {
		return mExtraEditText1;
	}

	public String getExtraEditText2() {
		return mExtraEditText2;
	}

	public String getExtraEditText3() {
		return mExtraEditText3;
	}

	public Date getDate() {
		return mDate;
	}

	public String getFormattedDate(Context context) {
		final TimeZone timeZone = (mTimeZone != null) ? mTimeZone : TimeZone.getDefault();
		java.text.DateFormat format = android.text.format.DateFormat.getDateFormat(context);
		format.setTimeZone(timeZone);
		return format.format(mDate);
	}

	public String getFormattedDate(Context context, String separator) {
		final TimeZone timeZone = (mTimeZone != null) ? mTimeZone : TimeZone.getDefault();
		java.text.DateFormat format = android.text.format.DateFormat.getDateFormat(context);
		format.setTimeZone(timeZone);
		String formattedDate = format.format(mDate);
		formattedDate = formattedDate.replace(DateUtils.getDateSeparator(context), separator);
		return formattedDate;
	}

	public TimeZone getTimeZone() {
		return mTimeZone;
	}

	public boolean isExpensable() {
		return mIsExpensable;
	}

	public boolean isFullPage() {
		return mIsFullPage;
	}

	public boolean isSelected() {
		return mIsSelected;
	}

	public WBCurrency getCurrency() {
		return mCurrency;
	}

	public String getCurrencyCode() {
		return getCurrency().getCurrencyCode();
	}

	public int getIndex() {
		return mIndex;
	}

	public void setTrip(TripRow trip) {
		mTrip = trip;
	}

	public void setPaymentMethod(PaymentMethod method) {
		mPaymentMethod = method;
	}

	void setName(String name) {
		mName = name;
	}

	void setCategory(String category) {
		mCategory = category;
	}

	void setComment(String comment) {
		mComment = comment;
	}

	void setDate(Date date) {
		mDate = date;
	}

	void setTimeZone(TimeZone timeZone) {
		mTimeZone = timeZone;
	}

	public void setFile(File file) {
		mFile = file;
	}

	/**
	 * This is identical to calling setFile
	 * 
	 * @param img
	 */
	public void setImage(File img) {
		mFile = img;
	}

	/**
	 * This is identical to calling setFile
	 * 
	 * @param pdf
	 */
	public void setPDF(File pdf) {
		mFile = pdf;
	}

	void setCurrency(WBCurrency currency) {
		mCurrency = currency;
	}

	void setPrice(String price) {
		mPrice = tryParse(price);
	}

	void setPrice(double price) {
		mPrice = new BigDecimal(price);
	}

	void setTax(String tax) {
		mTax = tryParse(tax);
	}

	void setTax(double tax) {
		mTax = new BigDecimal(tax);
	}

	private BigDecimal tryParse(String number) {
		if (TextUtils.isEmpty(number)) {
			return new BigDecimal(0);
		}
		try {
			return new BigDecimal(number.replace(",", "."));
		}
		catch (NumberFormatException e) {
			return new BigDecimal(0);
		}
	}

	void setIsExpenseable(boolean isExpenseable) {
		mIsExpensable = isExpenseable;
	}

	void setIsFullPage(boolean isFullPage) {
		mIsFullPage = isFullPage;
	}

	public void setIsSelected(boolean isSelected) {
		mIsSelected = isSelected;
	}

	void setSource(SourceEnum source) {
		mSource = source;
	}

	public boolean hasExtraEditText1() {
		return (mExtraEditText1 != null);
	}

	void setExtraEditText1(String extraEditText1) {
		if (!TextUtils.isEmpty(extraEditText1)) {
			mExtraEditText1 = extraEditText1.equalsIgnoreCase(DatabaseHelper.NO_DATA) ? null : extraEditText1;
		}
		else {
			mExtraEditText1 = null;
		}
	}

	public boolean hasExtraEditText2() {
		return (mExtraEditText2 != null);
	}

	void setExtraEditText2(String extraEditText2) {
		if (!TextUtils.isEmpty(extraEditText2)) {
			mExtraEditText2 = extraEditText2.equalsIgnoreCase(DatabaseHelper.NO_DATA) ? null : extraEditText2;
		}
		else {
			mExtraEditText2 = null;
		}
	}

	public boolean hasExtraEditText3() {
		return (mExtraEditText3 != null);
	}

	void setExtraEditText3(String extraEditText3) {
		if (!TextUtils.isEmpty(extraEditText3)) {
			mExtraEditText3 = extraEditText3.equalsIgnoreCase(DatabaseHelper.NO_DATA) ? null : extraEditText3;
		}
		else {
			mExtraEditText3 = null;
		}
	}

	private DecimalFormat getDecimalFormat() {
		if (mDecimalFormat == null) {
			mDecimalFormat = new DecimalFormat();
			mDecimalFormat.setMaximumFractionDigits(2);
			mDecimalFormat.setMinimumFractionDigits(2);
			mDecimalFormat.setGroupingUsed(false);
		}
		return mDecimalFormat;
	}

	public void setIndex(int index) {
		mIndex = index;
	}

	@Override
	@SuppressWarnings("deprecation")
	public String toString() {
		return this.getClass().getSimpleName() + "::\n" + "[" + "source => " + getSource() + "; \n" + "id => "
				+ getId() + "; \n" + "name => " + getName() + "; \n" + "category =>" + getCategory() + "; \n"
				+ "comment =>" + getComment() + "; \n" + "price =>" + getPrice() + "; \n" + "tax =>" + getTax()
				+ "; \n" + "filePath =>" + getFilePath() + "; \n" + "currency =>" + getCurrencyCode() + "; \n"
				+ "date =>" + getDate().toGMTString() + "; \n" + "isExpensable =>" + isExpensable() + "; \n"
				+ "isFullPage =>" + isFullPage() + "; \n" + "extraEditText1 =>" + getExtraEditText1() + "; \n"
				+ "extraEditText2 =>" + getExtraEditText2() + "; \n" + "extraEditText3 =>" + getExtraEditText3() + "]";
	}

	@Override
	public int hashCode() {
		return 31 * mId;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		ReceiptRow other = (ReceiptRow) obj;
		if (mId != other.mId) {
			return false;
		}
		return true;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeParcelable(getTrip(), flags);
		dest.writeParcelable(getPaymentMethod(), flags);
		dest.writeInt(getId());
		dest.writeString(getName());
		dest.writeString(getCategory());
		dest.writeString(getComment());
		dest.writeFloat(getPriceAsFloat());
		dest.writeFloat(getTaxAsFloat());
		dest.writeString(getFilePath());
		dest.writeLong(getDate().getTime());
		dest.writeString(getCurrencyCode());
		dest.writeByte((byte) (isExpensable() ? 1 : 0));
		dest.writeByte((byte) (isFullPage() ? 1 : 0));
		dest.writeString(getExtraEditText1());
		dest.writeString(getExtraEditText2());
		dest.writeString(getExtraEditText3());
		dest.writeInt(getIndex());
		dest.writeString(mTimeZone.getID());
	}

	public static final class Builder {

		private TripRow _trip;
		private PaymentMethod _paymentMethod;
		private File _file;
		private String _name, _category, _comment, _priceString, _taxString;
		private String _extraEditText1, _extraEditText2, _extraEditText3;
		private double _price, _tax;
		private Date _date;
		private TimeZone _timezone;
		private final int _id;
		private int _index;
		private boolean _isExpenseable, _isFullPage, _isSelected;
		private WBCurrency _currency;
		private SourceEnum _source;

		public Builder(int id) {
			_id = id;
			_index = -1;
			_source = SourceEnum.Undefined;
			_timezone = TimeZone.getDefault();
		}

		public Builder setTrip(TripRow trip) {
			_trip = trip;
			return this;
		}

		public Builder setPaymentMethod(PaymentMethod method) {
			_paymentMethod = method;
			return this;
		}

		public Builder setName(String name) {
			_name = name;
			return this;
		}

		public Builder setCategory(String category) {
			_category = category;
			return this;
		}

		public Builder setComment(String comment) {
			_comment = comment;
			return this;
		}

		/**
		 * Sets the price of this builder as a string (useful for user input)
		 * 
		 * @param price
		 *            - the desired price as a string
		 * @return the {@link Builder} instance for method chaining
		 */
		public Builder setPrice(String price) {
			_priceString = price;
			return this;
		}

		public Builder setPrice(double price) {
			_price = price;
			return this;
		}

		/**
		 * Sets the tax of this builder as a string (useful for user input)
		 * 
		 * @param price
		 *            - the desired tax as a string
		 * @return the {@link Builder} instance for method chaining
		 */
		public Builder setTax(String tax) {
			_taxString = tax;
			return this;
		}

		public Builder setTax(double tax) {
			_tax = tax;
			return this;
		}

		public Builder setFile(File file) {
			_file = file;
			return this;
		}

		public Builder setImage(File image) {
			_file = image;
			return this;
		}

		public Builder setPDF(File pdf) {
			_file = pdf;
			return this;
		}

		public Builder setDate(Date date) {
			_date = date;
			return this;
		}

		public Builder setDate(long datetime) {
			_date = new Date(datetime);
			return this;
		}

		public Builder setTimeZone(String timeZoneId) {
			if (timeZoneId != null) {
				_timezone = TimeZone.getTimeZone(timeZoneId);
			}
			return this;
		}

		public Builder setTimeZone(TimeZone timeZone) {
			_timezone = timeZone;
			return this;
		}

		public Builder setIsExpenseable(boolean isExpenseable) {
			_isExpenseable = isExpenseable;
			return this;
		}

		public Builder setIsFullPage(boolean isFullPage) {
			_isFullPage = isFullPage;
			return this;
		}

		public Builder setIsSelected(boolean isSelected) {
			_isSelected = isSelected;
			return this;
		}

		public Builder setCurrency(WBCurrency currency) {
			_currency = currency;
			return this;
		}

		public Builder setCurrency(String currencyCode) {
			_currency = WBCurrency.getInstance(currencyCode);
			return this;
		}

		public Builder setExtraEditText1(String extraEditText1) {
			_extraEditText1 = extraEditText1;
			return this;
		}

		public Builder setExtraEditText2(String extraEditText2) {
			_extraEditText2 = extraEditText2;
			return this;
		}

		public Builder setExtraEditText3(String extraEditText3) {
			_extraEditText3 = extraEditText3;
			return this;
		}

		public Builder setIndex(int index) {
			_index = index;
			return this;
		}

		// TODO: Use this method
		public Builder setSourceAsCache() {
			_source = SourceEnum.Cache;
			return this;
		}

		public ReceiptRow build() {
			ReceiptRow receipt = new ReceiptRow(_id);
			receipt.setTrip(_trip);
			receipt.setPaymentMethod(_paymentMethod);
			receipt.setName(_name);
			receipt.setCategory(_category);
			receipt.setComment(_comment);
			if (!TextUtils.isEmpty(_priceString)) {
				receipt.setPrice(_priceString);
			}
			else {
				receipt.setPrice(_price);
			}
			if (!TextUtils.isEmpty(_taxString)) {
				receipt.setTax(_taxString);
			}
			else {
				receipt.setTax(_tax);
			}
			receipt.setFile(_file);
			receipt.setExtraEditText1(_extraEditText1);
			receipt.setExtraEditText2(_extraEditText2);
			receipt.setExtraEditText3(_extraEditText3);
			receipt.setDate(_date);
			receipt.setTimeZone(_timezone);
			receipt.setIsExpenseable(_isExpenseable);
			receipt.setIsFullPage(_isFullPage);
			receipt.setIsSelected(_isSelected);
			receipt.setCurrency(_currency);
			receipt.setIndex(_index);
			receipt.setSource(_source);
			return receipt;
		}
	}

	public static Parcelable.Creator<ReceiptRow> CREATOR = new Parcelable.Creator<ReceiptRow>() {

		@Override
		public ReceiptRow createFromParcel(Parcel source) {
			return new ReceiptRow(source);
		}

		@Override
		public ReceiptRow[] newArray(int size) {
			return new ReceiptRow[size];
		}

	};

}