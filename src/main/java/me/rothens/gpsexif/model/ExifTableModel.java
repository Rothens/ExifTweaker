package me.rothens.gpsexif.model;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Rothens on 2017. 06. 20..
 */
public class ExifTableModel extends AbstractTableModel {

    private final String[] columns = {"Key", "Value"};
    List<ExifData> exifData;

    public ExifTableModel() {
        exifData = new ArrayList<>();
    }

    @Override
    public int getRowCount() {
        return exifData.size();
    }

    @Override
    public int getColumnCount() {
        return 2;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ExifData exifData = this.exifData.get(rowIndex);
        return columnIndex == 0 ? exifData.getKey() : exifData.getValue();
    }

    @Override
    public String getColumnName(int column) {
        return columns[column % columns.length];
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    public void clear() {
        exifData.clear();
        fireTableDataChanged();
    }

    public void setData(List<ExifData> list) {
        exifData.clear();
        exifData.addAll(list);
        fireTableDataChanged();
    }
}
