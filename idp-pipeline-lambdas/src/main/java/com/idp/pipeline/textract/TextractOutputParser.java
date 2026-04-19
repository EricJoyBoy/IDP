package com.idp.pipeline.textract;

import com.idp.common.model.BoundingBox;
import com.idp.common.model.ExtractedContent;
import com.idp.common.model.KeyValuePair;
import com.idp.common.model.Table;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.EntityType;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Parses the list of Textract Block objects into an {@link ExtractedContent} DTO.
 *
 * <p>Handles:
 * <ul>
 *   <li>Raw text from LINE blocks</li>
 *   <li>Key-value pairs from KEY_VALUE_SET blocks</li>
 *   <li>Tables from TABLE/CELL blocks (with multi-page merging via {@link MultiPageTableMerger})</li>
 *   <li>Bounding boxes for all elements</li>
 * </ul>
 */
public class TextractOutputParser {

    private static final Logger log = Logger.getLogger(TextractOutputParser.class.getName());

    private final MultiPageTableMerger tableMerger;

    public TextractOutputParser() {
        this.tableMerger = new MultiPageTableMerger();
    }

    /** Constructor for testing — allows injection of merger. */
    public TextractOutputParser(MultiPageTableMerger tableMerger) {
        this.tableMerger = tableMerger;
    }

    /**
     * Parses a list of Textract blocks into an {@link ExtractedContent} DTO.
     *
     * @param blocks the raw Textract blocks
     * @return normalized ExtractedContent
     */
    public ExtractedContent parse(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return ExtractedContent.builder()
                    .rawText("")
                    .keyValuePairs(Collections.emptyList())
                    .tables(Collections.emptyList())
                    .boundingBoxes(Collections.emptyList())
                    .build();
        }

        // Index all blocks by id for relationship traversal
        Map<String, Block> blockById = new HashMap<>();
        for (Block block : blocks) {
            blockById.put(block.id(), block);
        }

        String rawText = extractRawText(blocks);
        List<KeyValuePair> keyValuePairs = extractKeyValuePairs(blocks, blockById);
        List<Table> tables = extractTables(blocks, blockById);
        List<BoundingBox> boundingBoxes = extractBoundingBoxes(blocks);

        // Merge multi-page tables
        List<Table> mergedTables = tableMerger.merge(tables);

        return ExtractedContent.builder()
                .rawText(rawText)
                .keyValuePairs(keyValuePairs)
                .tables(mergedTables)
                .boundingBoxes(boundingBoxes)
                .build();
    }

    // -------------------------------------------------------------------------
    // Raw text
    // -------------------------------------------------------------------------

    private String extractRawText(List<Block> blocks) {
        return blocks.stream()
                .filter(b -> b.blockType() == BlockType.LINE)
                .map(b -> b.text() != null ? b.text() : "")
                .collect(Collectors.joining("\n"));
    }

    // -------------------------------------------------------------------------
    // Key-value pairs
    // -------------------------------------------------------------------------

    private List<KeyValuePair> extractKeyValuePairs(List<Block> blocks, Map<String, Block> blockById) {
        List<KeyValuePair> pairs = new ArrayList<>();

        for (Block block : blocks) {
            if (block.blockType() != BlockType.KEY_VALUE_SET) continue;
            if (!block.hasEntityTypes()) continue;
            // Only process KEY blocks (not VALUE blocks)
            boolean isKey = block.entityTypes().contains(EntityType.KEY);
            if (!isKey) continue;

            String keyText = resolveText(block, blockById);
            BoundingBox keyBb = toBoundingBox(block);
            double confidence = block.confidence() != null ? block.confidence() : 0.0;

            // Find the VALUE block via CHILD relationship
            String valueText = "";
            BoundingBox valueBb = null;
            if (block.hasRelationships()) {
                for (Relationship rel : block.relationships()) {
                    if (rel.type() == RelationshipType.VALUE) {
                        for (String valueId : rel.ids()) {
                            Block valueBlock = blockById.get(valueId);
                            if (valueBlock != null) {
                                valueText = resolveText(valueBlock, blockById);
                                valueBb = toBoundingBox(valueBlock);
                                if (valueBlock.confidence() != null) {
                                    confidence = Math.min(confidence, valueBlock.confidence());
                                }
                            }
                        }
                    }
                }
            }

            pairs.add(KeyValuePair.builder()
                    .key(keyText)
                    .value(valueText)
                    .confidenceScore((double) confidence / 100.0)
                    .keyBoundingBox(keyBb)
                    .valueBoundingBox(valueBb)
                    .build());
        }

        return pairs;
    }

    // -------------------------------------------------------------------------
    // Tables
    // -------------------------------------------------------------------------

    private List<Table> extractTables(List<Block> blocks, Map<String, Block> blockById) {
        List<Table> tables = new ArrayList<>();
        int tableIndex = 0;

        for (Block block : blocks) {
            if (block.blockType() != BlockType.TABLE) continue;

            tableIndex++;
            String tableId = "table-" + tableIndex;
            int page = block.page() != null ? block.page() : 1;
            BoundingBox tableBb = toBoundingBox(block);
            double tableConfidence = block.confidence() != null ? block.confidence() / 100.0 : 0.0;

            // Collect all CELL blocks for this table
            Map<String, Block> cellBlocks = new HashMap<>();
            if (block.hasRelationships()) {
                for (Relationship rel : block.relationships()) {
                    if (rel.type() == RelationshipType.CHILD) {
                        for (String childId : rel.ids()) {
                            Block child = blockById.get(childId);
                            if (child != null && child.blockType() == BlockType.CELL) {
                                cellBlocks.put(childId, child);
                            }
                        }
                    }
                }
            }

            // Determine grid dimensions
            int maxRow = 0;
            int maxCol = 0;
            for (Block cell : cellBlocks.values()) {
                if (cell.rowIndex() != null && cell.rowIndex() > maxRow) maxRow = cell.rowIndex();
                if (cell.columnIndex() != null && cell.columnIndex() > maxCol) maxCol = cell.columnIndex();
            }

            // Build row/column grid (1-indexed from Textract)
            List<List<String>> rows = new ArrayList<>();
            for (int r = 1; r <= maxRow; r++) {
                List<String> row = new ArrayList<>(Collections.nCopies(maxCol, ""));
                for (Block cell : cellBlocks.values()) {
                    if (cell.rowIndex() != null && cell.rowIndex() == r) {
                        int colIdx = cell.columnIndex() != null ? cell.columnIndex() - 1 : 0;
                        if (colIdx < maxCol) {
                            row.set(colIdx, resolveText(cell, blockById));
                        }
                    }
                }
                rows.add(row);
            }

            // First row as column headers (if present)
            List<String> columnHeaders = rows.isEmpty() ? Collections.emptyList() : rows.get(0);
            List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : Collections.emptyList();

            tables.add(Table.builder()
                    .tableId(tableId)
                    .rows(new ArrayList<>(dataRows))
                    .columnHeaders(new ArrayList<>(columnHeaders))
                    .boundingBox(tableBb)
                    .confidenceScore(tableConfidence)
                    .page(page)
                    .build());
        }

        return tables;
    }

    // -------------------------------------------------------------------------
    // Bounding boxes
    // -------------------------------------------------------------------------

    private List<BoundingBox> extractBoundingBoxes(List<Block> blocks) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (Block block : blocks) {
            BoundingBox bb = toBoundingBox(block);
            if (bb != null) {
                boxes.add(bb);
            }
        }
        return boxes;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the text content of a block by following CHILD relationships to WORD blocks.
     */
    private String resolveText(Block block, Map<String, Block> blockById) {
        if (block.text() != null && !block.text().isEmpty()) {
            return block.text();
        }
        if (!block.hasRelationships()) return "";

        StringBuilder sb = new StringBuilder();
        for (Relationship rel : block.relationships()) {
            if (rel.type() == RelationshipType.CHILD) {
                for (String childId : rel.ids()) {
                    Block child = blockById.get(childId);
                    if (child != null && child.blockType() == BlockType.WORD && child.text() != null) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(child.text());
                    }
                }
            }
        }
        return sb.toString();
    }

    private BoundingBox toBoundingBox(Block block) {
        if (block.geometry() == null || block.geometry().boundingBox() == null) return null;
        software.amazon.awssdk.services.textract.model.BoundingBox bb = block.geometry().boundingBox();
        return BoundingBox.builder()
                .left(bb.left() != null ? bb.left().doubleValue() : null)
                .top(bb.top() != null ? bb.top().doubleValue() : null)
                .width(bb.width() != null ? bb.width().doubleValue() : null)
                .height(bb.height() != null ? bb.height().doubleValue() : null)
                .page(block.page())
                .build();
    }
}
