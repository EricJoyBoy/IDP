package com.idp.common.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.idp.common.model.BoundingBox;
import com.idp.common.model.CategoryCandidate;
import com.idp.common.model.ClassificationResult;
import com.idp.common.model.DocumentDTO;
import com.idp.common.model.DocumentStatus;
import com.idp.common.model.Entity;
import com.idp.common.model.ExtractedContent;
import com.idp.common.model.KPI;
import com.idp.common.model.KeyValuePair;
import net.jqwik.api.*;
import org.assertj.core.api.Assertions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Property-based tests for round-trip serialization consistency of IDP DTOs.
 *
 * <p><b>Property 1: Round-trip serialization consistency</b><br>
 * <b>Validates: Requirements 4.5</b>
 *
 * <p>For every valid DocumentDTO {@code d}:
 * {@code deserialize(serialize(d)) == d}
 */
class DocumentDTORoundTripProperties {

    // -----------------------------------------------------------------------
    // Property 1: Round-trip serialization consistency (Req 4.5)
    // -----------------------------------------------------------------------

    @Property
    @Label("Round-trip: serialize then deserialize DocumentDTO produces equal object")
    void documentDtoRoundTrip(@ForAll("documentDTOs") DocumentDTO original) throws JsonProcessingException {
        String json = DocumentSerializer.serialize(original);
        DocumentDTO restored = DocumentSerializer.deserialize(json);
        Assertions.assertThat(restored).isEqualTo(original);
    }

    @Property
    @Label("Round-trip: serialize then deserialize ExtractedContent produces equal object")
    void extractedContentRoundTrip(@ForAll("extractedContents") ExtractedContent original) throws JsonProcessingException {
        String json = DocumentSerializer.serializeContent(original);
        ExtractedContent restored = DocumentSerializer.deserializeContent(json);
        Assertions.assertThat(restored).isEqualTo(original);
    }

    // -----------------------------------------------------------------------
    // Arbitraries / Generators
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<DocumentDTO> documentDTOs() {
        // jqwik Combinators.combine() supports max 8 args; split into two stages
        Arbitrary<DocumentDTO.DocumentDTOBuilder> stage1 = Combinators.combine(
                uuids(),
                tenantIds(),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(100).injectNull(0.1),
                documentFormats().injectNull(0.1),
                Arbitraries.longs().between(0L, 52_428_800L).injectNull(0.1),
                instants().injectNull(0.1),
                documentStatuses(),
                extractedContents().injectNull(0.2)
        ).as((docId, tenantId, s3Key, format, sizeBytes, uploadTs, status, content) ->
                DocumentDTO.builder()
                        .documentId(docId)
                        .tenantId(tenantId)
                        .s3Key(s3Key)
                        .format(format)
                        .sizeBytes(sizeBytes)
                        .uploadTimestamp(uploadTs)
                        .status(status)
                        .content(content)
        );

        return Combinators.combine(
                stage1,
                entities().list().ofMaxSize(5).injectNull(0.2),
                classificationResults().injectNull(0.2),
                kpis().list().ofMaxSize(5).injectNull(0.2),
                instants().injectNull(0.3)
        ).as((builder, entityList, classification, kpiList, processedTs) ->
                builder
                        .entities(entityList)
                        .classification(classification)
                        .kpis(kpiList)
                        .processedTimestamp(processedTs)
                        .build()
        );
    }

    @Provide
    Arbitrary<ExtractedContent> extractedContents() {
        return Combinators.combine(
                Arbitraries.strings().ofMaxLength(500).injectNull(0.1),
                keyValuePairs().list().ofMaxSize(5).injectNull(0.2),
                tables().list().ofMaxSize(3).injectNull(0.2),
                boundingBoxes().list().ofMaxSize(5).injectNull(0.2)
        ).as((rawText, kvPairs, tableList, bboxList) ->
                ExtractedContent.builder()
                        .rawText(rawText)
                        .keyValuePairs(kvPairs)
                        .tables(tableList)
                        .boundingBoxes(bboxList)
                        .build()
        );
    }

    @Provide
    Arbitrary<BoundingBox> boundingBoxes() {
        return Combinators.combine(
                Arbitraries.doubles().between(0.0, 1.0).injectNull(0.1),
                Arbitraries.doubles().between(0.0, 1.0).injectNull(0.1),
                Arbitraries.doubles().between(0.0, 1.0).injectNull(0.1),
                Arbitraries.doubles().between(0.0, 1.0).injectNull(0.1),
                Arbitraries.integers().between(1, 100).injectNull(0.1)
        ).as((left, top, width, height, page) ->
                BoundingBox.builder()
                        .left(left).top(top).width(width).height(height).page(page)
                        .build()
        );
    }

    @Provide
    Arbitrary<KeyValuePair> keyValuePairs() {
        return Combinators.combine(
                Arbitraries.strings().ofMaxLength(100).injectNull(0.1),
                Arbitraries.strings().ofMaxLength(200).injectNull(0.1),
                confidenceScores().injectNull(0.1),
                boundingBoxes().injectNull(0.2),
                boundingBoxes().injectNull(0.2)
        ).as((key, value, score, keyBbox, valueBbox) ->
                KeyValuePair.builder()
                        .key(key).value(value).confidenceScore(score)
                        .keyBoundingBox(keyBbox).valueBoundingBox(valueBbox)
                        .build()
        );
    }

    @Provide
    Arbitrary<com.idp.common.model.Table> tables() {
        Arbitrary<List<List<String>>> rows = Arbitraries.strings().ofMaxLength(50)
                .injectNull(0.1)
                .list().ofMaxSize(3)
                .list().ofMaxSize(3);

        return Combinators.combine(
                Arbitraries.strings().alpha().ofMaxLength(20).injectNull(0.1),
                rows.injectNull(0.2),
                Arbitraries.strings().ofMaxLength(30).injectNull(0.1).list().ofMaxSize(5).injectNull(0.2),
                boundingBoxes().injectNull(0.2),
                confidenceScores().injectNull(0.1),
                Arbitraries.integers().between(1, 100).injectNull(0.1)
        ).as((tableId, tableRows, headers, bbox, score, page) ->
                com.idp.common.model.Table.builder()
                        .tableId(tableId).rows(tableRows).columnHeaders(headers)
                        .boundingBox(bbox).confidenceScore(score).page(page)
                        .build()
        );
    }

    @Provide
    Arbitrary<Entity> entities() {
        return Combinators.combine(
                Arbitraries.of("ORGANIZATION", "DATE", "QUANTITY", "OTHER").injectNull(0.1),
                Arbitraries.strings().ofMaxLength(200).injectNull(0.1),
                boundingBoxes().injectNull(0.2),
                confidenceScores().injectNull(0.1)
        ).as((type, value, position, score) ->
                Entity.builder()
                        .type(type).value(value).position(position).confidenceScore(score)
                        .build()
        );
    }

    @Provide
    Arbitrary<ClassificationResult> classificationResults() {
        return Combinators.combine(
                Arbitraries.of("bilancio", "conto_economico", "rendiconto", "contratto", "fattura", "altro").injectNull(0.1),
                confidenceScores().injectNull(0.1),
                categoryCandidates().list().ofMaxSize(3).injectNull(0.3)
        ).as((category, score, candidates) ->
                ClassificationResult.builder()
                        .category(category).confidenceScore(score).topCandidates(candidates)
                        .build()
        );
    }

    @Provide
    Arbitrary<CategoryCandidate> categoryCandidates() {
        return Combinators.combine(
                Arbitraries.of("bilancio", "conto_economico", "rendiconto", "contratto", "fattura", "altro").injectNull(0.1),
                confidenceScores().injectNull(0.1)
        ).as((category, score) ->
                CategoryCandidate.builder().category(category).confidenceScore(score).build()
        );
    }

    @Provide
    Arbitrary<KPI> kpis() {
        return Combinators.combine(
                Arbitraries.of("ricavi", "EBITDA", "utile_netto", "totale_attivo").injectNull(0.1),
                bigDecimals().injectNull(0.1),
                Arbitraries.of("EUR", "USD", "%", null),
                confidenceScores().injectNull(0.1)
        ).as((name, value, unit, score) ->
                KPI.builder().name(name).value(value).unit(unit).confidenceScore(score).build()
        );
    }

    // -----------------------------------------------------------------------
    // Primitive generators
    // -----------------------------------------------------------------------

    private Arbitrary<String> uuids() {
        return Arbitraries.of(
                "550e8400-e29b-41d4-a716-446655440000",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
                "6ba7b812-9dad-11d1-80b4-00c04fd430c8"
        );
    }

    private Arbitrary<String> tenantIds() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
    }

    private Arbitrary<String> documentFormats() {
        return Arbitraries.of("PDF", "PNG", "JPEG", "TIFF");
    }

    private Arbitrary<DocumentStatus> documentStatuses() {
        return Arbitraries.of(DocumentStatus.values());
    }

    private Arbitrary<Double> confidenceScores() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    private Arbitrary<BigDecimal> bigDecimals() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-999999999.99"), new BigDecimal("999999999.99"))
                .ofScale(2);
    }

    private Arbitrary<Instant> instants() {
        return Arbitraries.longs()
                .between(0L, 4_102_444_800L)
                .map(Instant::ofEpochSecond);
    }
}
