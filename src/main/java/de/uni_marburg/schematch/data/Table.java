package de.uni_marburg.schematch.data;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
public class Table {
    private final String name;
    private final List<String> labels;
    private List<Column> columns;
    private String path;
    private int offset;
    @Getter
    private Database database;

    public Table(String name, List<String> labels, List<Column> columns, String path, Database database) {
        this.name = name;
        this.labels = labels;
        this.columns = columns;
        this.path = path;

        for (Column column : this.columns) {
            column.setTable(this);
        }

        this.database = database;
    }

    public int getNumColumns() {
        return this.columns.size();
    }

    public Column getColumn(int n) {
        return this.columns.get(n);
    }

    public int getColumnIndex(Column column) {
        return this.columns.indexOf(column);
    }

}