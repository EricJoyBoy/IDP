package com.idp.pipeline.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Replicates KPIs and classification results to RDS PostgreSQL in a single JDBC transaction.
 *
 * <p>On any SQL failure the transaction is rolled back and the exception is rethrown so the
 * caller ({@link PersistenceHandler}) can mark the document as {@code PERSISTENCE_ERROR}.
 *
 * <p>Expected table schemas (DDL not managed here):
 * <pre>
 * document_classifications(document_id, tenant_id, category, confidence_score, persisted_at)
 * document_kpis(document_id, tenant_id, kpi_name, kpi_value, kpi_unit, confidence_score, persisted_at)
 * </pre>
 */
public class RdsPersistenceService {

    private static final Logger log = Logger.getLogger(RdsPersistenceService.class.getName());

    private static final String INSERT_CLASSIFICATION =
            "INSERT INTO document_classifications (document_id, tenant_id, category, confidence_score, persisted_at) " +
            "VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (document_id, tenant_id) DO UPDATE SET " +
            "category = EXCLUDED.category, confidence_score = EXCLUDED.confidence_score, persisted_at = EXCLUDED.persisted_at";

    private static final String INSERT_KPI =
            "INSERT INTO document_kpis (document_id, tenant_id, kpi_name, kpi_value, kpi_unit, confidence_score, persisted_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (document_id, tenant_id, kpi_name) DO UPDATE SET " +
            "kpi_value = EXCLUDED.kpi_value, kpi_unit = EXCLUDED.kpi_unit, " +
            "confidence_score = EXCLUDED.confidence_score, persisted_at = EXCLUDED.persisted_at";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public RdsPersistenceService(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Writes classification and KPIs to RDS in a single transaction.
     * Rolls back and rethrows on any failure.
     *
     * @param documentId    document identifier
     * @param tenantId      tenant identifier
     * @param classification classification map (keys: category, confidenceScore)
     * @param kpis          list of KPI maps (keys: name, value, unit, confidenceScore)
     * @param persistedAt   ISO-8601 timestamp
     * @throws RdsPersistenceException if the transaction fails
     */
    @SuppressWarnings("unchecked")
    public void persist(String documentId, String tenantId,
                        Object classification, Object kpis,
                        String persistedAt) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            conn.setAutoCommit(false);
            try {
                if (classification != null) {
                    insertClassification(conn, documentId, tenantId, classification, persistedAt);
                }
                if (kpis instanceof List) {
                    insertKpis(conn, documentId, tenantId, (List<Object>) kpis, persistedAt);
                }
                conn.commit();
                log.info(String.format("RDS transaction committed for documentId=%s", documentId));
            } catch (Exception e) {
                log.severe(String.format("RDS transaction failed for documentId=%s, rolling back: %s",
                        documentId, e.getMessage()));
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    log.severe("Rollback failed: " + rollbackEx.getMessage());
                }
                throw new RdsPersistenceException("RDS persistence failed for documentId=" + documentId, e);
            }
        } catch (RdsPersistenceException e) {
            throw e;
        } catch (SQLException e) {
            throw new RdsPersistenceException("Cannot obtain RDS connection for documentId=" + documentId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void insertClassification(Connection conn, String documentId, String tenantId,
                                      Object classificationObj, String persistedAt) throws SQLException {
        Map<String, Object> cls = (Map<String, Object>) classificationObj;
        String category = (String) cls.get("category");
        Object confidenceRaw = cls.get("confidenceScore");
        Double confidence = confidenceRaw != null ? ((Number) confidenceRaw).doubleValue() : null;

        try (PreparedStatement ps = conn.prepareStatement(INSERT_CLASSIFICATION)) {
            ps.setString(1, documentId);
            ps.setString(2, tenantId);
            ps.setString(3, category);
            if (confidence != null) {
                ps.setDouble(4, confidence);
            } else {
                ps.setNull(4, java.sql.Types.DOUBLE);
            }
            ps.setString(5, persistedAt);
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private void insertKpis(Connection conn, String documentId, String tenantId,
                            List<Object> kpiList, String persistedAt) throws SQLException {
        for (Object kpiObj : kpiList) {
            if (!(kpiObj instanceof Map)) continue;
            Map<String, Object> kpi = (Map<String, Object>) kpiObj;
            String name = (String) kpi.get("name");
            Object valueRaw = kpi.get("value");
            BigDecimal value = valueRaw != null ? new BigDecimal(valueRaw.toString()) : null;
            String unit = (String) kpi.get("unit");
            Object confidenceRaw = kpi.get("confidenceScore");
            Double confidence = confidenceRaw != null ? ((Number) confidenceRaw).doubleValue() : null;

            try (PreparedStatement ps = conn.prepareStatement(INSERT_KPI)) {
                ps.setString(1, documentId);
                ps.setString(2, tenantId);
                ps.setString(3, name);
                if (value != null) {
                    ps.setBigDecimal(4, value);
                } else {
                    ps.setNull(4, java.sql.Types.NUMERIC);
                }
                ps.setString(5, unit);
                if (confidence != null) {
                    ps.setDouble(6, confidence);
                } else {
                    ps.setNull(6, java.sql.Types.DOUBLE);
                }
                ps.setString(7, persistedAt);
                ps.executeUpdate();
            }
        }
    }
}
