package com.deathrayresearch.outlier.columns;

import com.deathrayresearch.outlier.Table;
import com.deathrayresearch.outlier.aggregator.StringReduceUtils;
import com.deathrayresearch.outlier.filter.text.StringFilters;
import com.deathrayresearch.outlier.io.TypeUtils;
import com.deathrayresearch.outlier.mapper.StringMapUtils;
import com.deathrayresearch.outlier.store.ColumnMetadata;
import com.deathrayresearch.outlier.util.DictionaryMap;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * A column in a base table that contains float values
 */
public class CategoryColumn extends AbstractColumn
        implements StringMapUtils, StringFilters, StringReduceUtils {

  public static final String MISSING_VALUE = (String) ColumnType.CAT.getMissingValue();

  private static int DEFAULT_ARRAY_SIZE = 128;

  // For internal iteration. What element are we looking at right now
  private int pointer = 0;

  // TODO(lwhite) initialize the unique value id number to the smallest possible short to maximize range
  private short id = 0;

  // holds a key for each row in the table. the key can be used to lookup the backing string value
  private ShortList values;

  // a bidirectional map of keys to backing string values.
  private DictionaryMap lookupTable = new DictionaryMap();

  public static CategoryColumn create(String name) {
    return create(name, DEFAULT_ARRAY_SIZE);
  }

  public static CategoryColumn create(String name, int size) {
    return new CategoryColumn(name, size);
  }

  public static CategoryColumn create(String name, List<String> categories) {
    CategoryColumn column = new CategoryColumn(name, categories.size());
    for (String string : categories) {
      column.add(string);
    }
    return column;
  }

  private CategoryColumn(String name) {
    super(name);
    values = new ShortArrayList(DEFAULT_ARRAY_SIZE);
  }

  public CategoryColumn(ColumnMetadata metadata) {
    super(metadata);
    values = new ShortArrayList(DEFAULT_ARRAY_SIZE);
  }

  public CategoryColumn(String name, int size) {
    super(name);
    values = new ShortArrayList(size);
  }

  @Override
  public ColumnType type() {
    return ColumnType.CAT;
  }

  @Override
  public boolean hasNext() {
    return pointer < values.size();
  }

  public String next() {
    return lookupTable.get(values.getShort(pointer++));
  }

  @Override
  public String getString(int row) {
    return get(row);
  }

  @Override
  public CategoryColumn emptyCopy() {
    return new CategoryColumn(name());
  }

  @Override
  public void reset() {
    pointer = 0;
  }

  // TODO(lwhite): review if reference assignment of data (values, lookupTable) is appropriate or copy needed
  private CategoryColumn copy() {
    CategoryColumn copy = emptyCopy();
    copy.lookupTable = this.lookupTable;
    copy.values = this.values;
    return copy;
  }

  @Override
  public Column sortAscending() {
    CategoryColumn copy = this.copy();
    Arrays.sort(copy.values.toArray());
    return copy;
  }

  // TODO(lwhite): Implement sorting
  @Override
  public Column sortDescending() {
    CategoryColumn copy = this.copy();
/*
    Arrays.sort(copy.data);
    Primitive.sort(copy.data, (d1, d2) -> Float.compare(d2, d1), false);
*/
    return copy;
  }

  /**
   * Returns the number of elements (a.k.a. rows or cells) in the column
   */
  @Override
  public int size() {
    return values.size();
  }

  /**
   * Returns the value at rowIndex in this column. The index is zero-based.
   *
   * @throws IndexOutOfBoundsException if the given rowIndex is not in the column
   */
  public String get(int rowIndex) {
    short k = values.getShort(rowIndex);
    return lookupTable.get(k);
  }

  public short getKey(int index) {
    return values.getShort(index);
  }

  @Override
  public Table summary() {
    Table t = new Table(name());
    CategoryColumn categories = CategoryColumn.create("Category");
    IntColumn counts = IntColumn.create("Count");

    Object2IntOpenHashMap<String> valueToKey = new Object2IntOpenHashMap<>();

    while (this.hasNext()) {
      String category = this.next();
      if (valueToKey.containsKey(category)) {
        valueToKey.addTo(category, 1);
      } else {
        valueToKey.put(category, 1);
      }
    }
    for (Object2IntOpenHashMap.Entry<String> entry : valueToKey.object2IntEntrySet()) {
      categories.add(entry.getKey());
      counts.add(entry.getIntValue());
    }
    t.addColumn(categories);
    t.addColumn(counts);
    reset();
    return t;
  }

  @Override
  public void clear() {
    values.clear();
    lookupTable.clear();
  }

  public void set(int rowIndex, String stringValue) {
    boolean b = lookupTable.contains(stringValue);
    short valueId;
    if (!b) {
// TODO(lwhite): synchronize id() or column-level saveTable lock so we can increment id safely without atomic integer objects
      valueId = id++;
      lookupTable.put(valueId, stringValue);
    } else {
      valueId = lookupTable.get(stringValue);
    }

    values.set(rowIndex, valueId);
  }

  @Override
  public int countUnique() {
    return lookupTable.size();
  }

  public void add(String stringValue) {
    boolean b = lookupTable.contains(stringValue);
    short valueId;
    if (!b) {
      valueId = id++;
      lookupTable.put(valueId, stringValue);
    } else {
      valueId = lookupTable.get(stringValue);
    }
    values.add(valueId);
  }

  public final IntComparator rowComparator = new IntComparator() {

    @Override
    public int compare(int i, int i1) {
      //return getString(values.getShort(i)).compareTo(getString(values.getShort(i1)));
      String f1 = getString(i);
      String f2 = getString(i1);
      return f1.compareTo(f2);
/*
      String f1 = getString(values.getShort(i));
      String f2 = getString(values.getShort(i1));
      return f1.compareTo(f2);
*/
    }

    @Override
    public int compare(Integer i, Integer i1) {
      //return getString(values.getShort(i)).compareTo(getString(values.getShort(i1)));
      return getString(i).compareTo(getString(i1));
    }
  };

  public static String convert(String stringValue) {
    if (Strings.isNullOrEmpty(stringValue) || TypeUtils.MISSING_INDICATORS.contains(stringValue)) {
      return MISSING_VALUE;
    }
    return stringValue;
  }

  public void addCell(String object) {
    try {
      add(convert(object));
    } catch (NullPointerException e) {
      throw new RuntimeException(name() + ": "
          + String.valueOf(object) + ": "
          + e.getMessage());
    }
  }

  @Override
  public IntComparator rowComparator() {
    return rowComparator;
  }

  @Override
  public boolean isEmpty() {
    return values.isEmpty();
  }

  public RoaringBitmap isEqualTo(String string) {
    RoaringBitmap results = new RoaringBitmap();
    int i = 0;
    while (hasNext()) {
      if (string.equals(next())) {
        results.add(i);
      }
      i++;
    }
    reset();
    return results;
  }

  /**
   * Returns a list of boolean columns suitable for use as dummy variables in, for example, regression analysis,
   * where a column of categorical data must be encoded as a list of columns, such that each column represents a single
   * category and indicates whether it is present (1) or not present (0)
   */
  public List<BooleanColumn> getDummies() {
    List<BooleanColumn> results = new ArrayList<>();

    // create the necessary columns
    for (Short2ObjectMap.Entry<String> entry: lookupTable.keyToValueMap().short2ObjectEntrySet()) {
      BooleanColumn column = BooleanColumn.create(entry.getValue());
      results.add(column);
    }

    // iterate over the values, updating the dummy variable columns as appropriate
    while(hasNext()) {
      String category = next();
      for (BooleanColumn column : results) {
        if (category.equals(column.name())) {
          //TODO(lwhite): update the correct row more efficiently, by using set rather than add & only updating true
          column.add(true);
        } else {
          column.add(false);
        }
      }
    }
    reset();
    return results;
  }


  public int getInt(int rowNumber) {
    return values.get(rowNumber);
  }

  public CategoryColumn unique() {
    List<String> strings = new ArrayList<>();
    strings.addAll(lookupTable.categories());
    return CategoryColumn.create(name() + " Unique values", strings);

  }

  public DictionaryMap dictionaryMap() {
    return lookupTable;
  }

  @Override
  public String toString() {
    return "Category column: " + name();
  }

  public int[] indexes() {
    int[] rowIndexes = new int[size()];
    for (int i = 0; i < size(); i++) {
      rowIndexes[i] = i;
    }
    return rowIndexes;
  }

  public CategoryColumn replaceAll(String[] regexArray, String replacement) {

    CategoryColumn newColumn = CategoryColumn.create(name() + "[repl]", this.size());

    for (int r = 0; r < size(); r++) {
      String value = get(r);
      for (String regex : regexArray) {
        value = value.replaceAll(regex, replacement);
      }
      newColumn.add(value);
    }
    return newColumn;
  }

  public CategoryColumn tokenizeAndSort(String separator) {
    CategoryColumn newColumn = CategoryColumn.create(name() + "[sorted]", this.size());

    for (int r = 0; r < size(); r++) {
      String value = get(r);

      List<String> tokens =
              new ArrayList<>(Splitter.on(separator).trimResults().splitToList(value));
      Collections.sort(tokens);
      value = String.join(" ", tokens);
      newColumn.add(value);
    }
    return newColumn;
  }

  /**
   * Splits on Whitespace and returns the lexicographically sorted result
   */
  public CategoryColumn tokenizeAndSort() {
    CategoryColumn newColumn = CategoryColumn.create(name() + "[sorted]", this.size());

    for (int r = 0; r < size(); r++) {
      String value = get(r);

      List<String> tokens =
              new ArrayList<>(Splitter.on(CharMatcher.WHITESPACE).trimResults().splitToList(value));
      Collections.sort(tokens);
      value = String.join(" ", tokens);
      newColumn.add(value);
    }
    return newColumn;
  }

  public CategoryColumn tokenizeAndRemoveDuplicates() {
    CategoryColumn newColumn = CategoryColumn.create(name() + "[repl]", this.size());

    for (int r = 0; r < size(); r++) {
      String value = get(r);

      List<String> tokens = Splitter.on(CharMatcher.WHITESPACE).trimResults().splitToList(value);

      value = String.join(" ", new HashSet<>(tokens));
      newColumn.add(value);
    }
    return newColumn;
  }
}
