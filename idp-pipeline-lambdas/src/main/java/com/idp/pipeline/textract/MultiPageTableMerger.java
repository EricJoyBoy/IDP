package com.idp.pipeline.textract;

import com.idp.common.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges table fragments distributed across multiple pages into a single coherent table.
 *
 * <p>Tables are considered part of the same logical table when their {@code tableId} shares
 * the same base prefix (e.g. {@code table-1}, {@code table-1-p2}, {@code table-1-p3}).
 * In practice, the {@link TextractOutputParser} assigns sequential IDs ({@code table-1},
 * {@code table-2}, …). For multi-page documents, tables that appear on consecutive pages
 * with the same column structure are merged by appending their data rows.
 *
 * <p>Merging strategy:
 * <ol>
 *   <li>Group tables by their base ID prefix (everything before the last {@code -pN} suffix,
 *       or the full ID if no suffix is present).</li>
 *   <li>Within each group, sort by page number.</li>
 *   <li>Combine all data rows; keep the column headers from the first fragment.</li>
 *   <li>Keep the bounding box and confidence score of the first fragment.</li>
 * </ol>
 */
public class MultiPageTableMerger {

    private static final Logger log = Logger.getLogger(MultiPageTableMerger.class.getName());

    /**
     * Pattern that matches a trailing {@code -pN} suffix (e.g. {@code table-1-p2}).
     * Used to detect continuation fragments produced by external pre-processors.
     */
    private static final Pattern PAGE_SUFFIX = Pattern.compile("^(.+)-p(\\d+)$");

    /**
     * Merges multi-page table fragments.
     *
     * @param tables list of tables as parsed from Textract blocks (may contain fragments)
     * @return list of merged tables; single-page tables are returned unchanged
     */
    public List<Table> merge(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<>();
        }

        // Group tables by base ID, preserving insertion order
        Map<String, List<Table>> groups = new LinkedHashMap<>();
        for (Table table : tables) {
            String baseId = extractBaseId(table.getTableId());
            groups.computeIfAbsent(baseId, k -> new ArrayList<>()).add(table);
        }

        List<Table> merged = new ArrayList<>();
        for (Map.Entry<String, List<Table>> entry : groups.entrySet()) {
            List<Table> group = entry.getValue();
            if (group.size() == 1) {
                merged.add(group.get(0));
            } else {
                merged.add(mergeGroup(entry.getKey(), group));
            }
        }

        return merged;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the base ID from a table ID, stripping any {@code -pN} suffix.
     */
    String extractBaseId(String tableId) {
        if (tableId == null) return "";
        Matcher m = PAGE_SUFFIX.matcher(tableId);
        return m.matches() ? m.group(1) : tableId;
    }

    /**
     * Merges a group of table fragments (all sharing the same base ID) into one table.
     * Fragments are sorted by page number before merging.
     */
    private Table mergeGroup(String baseId, List<Table> fragments) {
        // Sort by page number (nulls last)
        fragments.sort((a, b) -> {
            int pa = a.getPage() != null ? a.getPage() : Integer.MAX_VALUE;
            int pb = b.getPage() != null ? b.getPage() : Integer.MAX_VALUE;
            return Integer.compare(pa, pb);
        });

        Table first = fragments.get(0);
        List<List<String>> allRows = new ArrayList<>();

        for (int i = 0; i < fragments.size(); i++) {
            Table fragment = fragments.get(i);
            List<List<String>> rows = fragment.getRows();
            if (rows != null) {
                if (i == 0) {
                    // Include all rows from the first fragment (headers already separated)
                    allRows.addAll(rows);
                } else {
                    // For continuation pages: skip the first row if it duplicates the header
                    List<String> headers = first.getColumnHeaders();
                    List<List<String>> fragmentRows = rows;
                    if (!fragmentRows.isEmpty() && headers != null && fragmentRows.get(0).equals(headers)) {
                        fragmentRows = fragmentRows.subList(1, fragmentRows.size());
                    }
                    allRows.addAll(fragmentRows);
                }
            }
        }

        log.info(String.format("Merged %d table fragments for baseId=%s into %d rows",
                fragments.size(), baseId, allRows.size()));

        return Table.builder()
                .tableId(baseId)
                .rows(allRows)
                .columnHeaders(first.getColumnHeaders())
                .boundingBox(first.getBoundingBox())
                .confidenceScore(first.getConfidenceScore())
                .page(first.getPage())
                .build();
    }
}
