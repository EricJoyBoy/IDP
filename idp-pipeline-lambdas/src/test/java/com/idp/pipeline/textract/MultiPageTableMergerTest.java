package com.idp.pipeline.textract;

import com.idp.common.model.BoundingBox;
import com.idp.common.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MultiPageTableMerger}.
 *
 * Covers:
 * - Single-page tables returned unchanged
 * - Multi-page tables merged by -pN suffix
 * - Duplicate header row removed from continuation pages
 * - Fragments sorted by page number before merging
 * - Empty / null input handled gracefully
 */
class MultiPageTableMergerTest {

    private MultiPageTableMerger merger;

    @BeforeEach
    void setUp() {
        merger = new MultiPageTableMerger();
    }

    // -------------------------------------------------------------------------
    // extractBaseId
    // -------------------------------------------------------------------------

    @Test
    void extractBaseId_noSuffix_returnsFullId() {
        assertThat(merger.extractBaseId("table-1")).isEqualTo("table-1");
    }

    @Test
    void extractBaseId_withPageSuffix_stripsIt() {
        assertThat(merger.extractBaseId("table-1-p2")).isEqualTo("table-1");
        assertThat(merger.extractBaseId("table-3-p10")).isEqualTo("table-3");
    }

    @Test
    void extractBaseId_null_returnsEmpty() {
        assertThat(merger.extractBaseId(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // merge – empty / null
    // -------------------------------------------------------------------------

    @Test
    void merge_nullInput_returnsEmptyList() {
        assertThat(merger.merge(null)).isEmpty();
    }

    @Test
    void merge_emptyInput_returnsEmptyList() {
        assertThat(merger.merge(Collections.emptyList())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // merge – single-page tables
    // -------------------------------------------------------------------------

    @Test
    void merge_singlePageTable_returnedUnchanged() {
        Table t = table("table-1", 1,
                headers("Col A", "Col B"),
                rows(row("v1", "v2"), row("v3", "v4")));

        List<Table> result = merger.merge(List.of(t));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTableId()).isEqualTo("table-1");
        assertThat(result.get(0).getRows()).hasSize(2);
    }

    @Test
    void merge_multipleSinglePageTables_allReturnedUnchanged() {
        Table t1 = table("table-1", 1, headers("A"), rows(row("1")));
        Table t2 = table("table-2", 1, headers("B"), rows(row("2")));

        List<Table> result = merger.merge(List.of(t1, t2));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Table::getTableId).containsExactly("table-1", "table-2");
    }

    // -------------------------------------------------------------------------
    // merge – multi-page tables via -pN suffix
    // -------------------------------------------------------------------------

    @Test
    void merge_twoPageFragments_rowsCombined() {
        Table page1 = table("table-1", 1,
                headers("Name", "Amount"),
                rows(row("Alice", "100"), row("Bob", "200")));

        Table page2 = table("table-1-p2", 2,
                headers("Name", "Amount"),
                rows(row("Charlie", "300"), row("Dave", "400")));

        List<Table> result = merger.merge(List.of(page1, page2));

        assertThat(result).hasSize(1);
        Table merged = result.get(0);
        assertThat(merged.getTableId()).isEqualTo("table-1");
        assertThat(merged.getColumnHeaders()).containsExactly("Name", "Amount");
        // page1 rows + page2 rows (header row of page2 is duplicate → stripped)
        assertThat(merged.getRows()).hasSize(4);
        assertThat(merged.getRows().get(0)).containsExactly("Alice", "100");
        assertThat(merged.getRows().get(2)).containsExactly("Charlie", "300");
    }

    @Test
    void merge_threePageFragments_allRowsCombined() {
        Table p1 = table("table-1", 1, headers("X"), rows(row("a"), row("b")));
        Table p2 = table("table-1-p2", 2, headers("X"), rows(row("c")));
        Table p3 = table("table-1-p3", 3, headers("X"), rows(row("d"), row("e")));

        List<Table> result = merger.merge(List.of(p1, p2, p3));

        assertThat(result).hasSize(1);
        // 2 + 1 + 2 = 5 rows (duplicate headers stripped from p2 and p3)
        assertThat(result.get(0).getRows()).hasSize(5);
    }

    @Test
    void merge_fragmentsOutOfOrder_sortedByPageBeforeMerge() {
        // Provide fragments in reverse page order
        Table p2 = table("table-1-p2", 2, headers("Col"), rows(row("second")));
        Table p1 = table("table-1", 1, headers("Col"), rows(row("first")));

        List<Table> result = merger.merge(List.of(p2, p1));

        assertThat(result).hasSize(1);
        List<List<String>> rows = result.get(0).getRows();
        assertThat(rows.get(0)).containsExactly("first");
        assertThat(rows.get(1)).containsExactly("second");
    }

    @Test
    void merge_continuationPageWithoutDuplicateHeader_allRowsKept() {
        // page2 does NOT repeat the header row
        Table p1 = table("table-1", 1, headers("A", "B"), rows(row("1", "2")));
        Table p2 = table("table-1-p2", 2, headers("A", "B"), rows(row("3", "4"), row("5", "6")));
        // Manually clear headers from p2 rows to simulate no-duplicate scenario
        Table p2NoHeader = Table.builder()
                .tableId("table-1-p2")
                .page(2)
                .columnHeaders(List.of("A", "B"))
                .rows(List.of(List.of("3", "4"), List.of("5", "6")))
                .confidenceScore(0.9)
                .build();

        List<Table> result = merger.merge(List.of(p1, p2NoHeader));

        assertThat(result).hasSize(1);
        // p1: 1 row, p2: 2 rows (no duplicate header to strip) → 3 total
        assertThat(result.get(0).getRows()).hasSize(3);
    }

    @Test
    void merge_metadataFromFirstFragment_preserved() {
        BoundingBox bb = BoundingBox.builder().left(0.1).top(0.2).width(0.5).height(0.3).page(1).build();
        Table p1 = Table.builder()
                .tableId("table-2")
                .page(1)
                .columnHeaders(List.of("H"))
                .rows(List.of(List.of("r1")))
                .boundingBox(bb)
                .confidenceScore(0.95)
                .build();
        Table p2 = Table.builder()
                .tableId("table-2-p2")
                .page(2)
                .columnHeaders(List.of("H"))
                .rows(List.of(List.of("r2")))
                .boundingBox(BoundingBox.builder().left(0.0).top(0.0).width(1.0).height(1.0).page(2).build())
                .confidenceScore(0.80)
                .build();

        List<Table> result = merger.merge(List.of(p1, p2));

        assertThat(result).hasSize(1);
        Table merged = result.get(0);
        assertThat(merged.getBoundingBox()).isEqualTo(bb);
        assertThat(merged.getConfidenceScore()).isEqualTo(0.95);
        assertThat(merged.getPage()).isEqualTo(1);
    }

    @Test
    void merge_mixedSingleAndMultiPage_correctGrouping() {
        Table single = table("table-1", 1, headers("S"), rows(row("s1")));
        Table multi1 = table("table-2", 2, headers("M"), rows(row("m1")));
        Table multi2 = table("table-2-p2", 3, headers("M"), rows(row("m2")));

        List<Table> result = merger.merge(List.of(single, multi1, multi2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTableId()).isEqualTo("table-1");
        assertThat(result.get(1).getTableId()).isEqualTo("table-2");
        assertThat(result.get(1).getRows()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Table table(String id, int page, List<String> headers, List<List<String>> rows) {
        return Table.builder()
                .tableId(id)
                .page(page)
                .columnHeaders(headers)
                .rows(rows)
                .confidenceScore(0.9)
                .build();
    }

    private List<String> headers(String... values) {
        return Arrays.asList(values);
    }

    @SafeVarargs
    private List<List<String>> rows(List<String>... rows) {
        return Arrays.asList(rows);
    }

    private List<String> row(String... values) {
        return Arrays.asList(values);
    }
}
