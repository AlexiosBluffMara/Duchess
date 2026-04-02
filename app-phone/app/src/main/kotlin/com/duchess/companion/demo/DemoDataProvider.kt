package com.duchess.companion.demo

import com.duchess.companion.model.SafetyAlert

/**
 * Provides realistic demo data for the Duchess companion app.
 * Used when DEMO_MODE is enabled so the app can run without real Meta glasses hardware.
 */
object DemoDataProvider {

    fun getSampleAlerts(): List<SafetyAlert> {
        val now = System.currentTimeMillis()
        return listOf(
            SafetyAlert(
                id = "alert-001",
                violationType = "NO_HARD_HAT",
                severity = 5,
                zoneId = "zone-A-framing",
                timestamp = now - 12 * 60_000,
                messageEn = "Worker detected without hard hat in framing zone",
                messageEs = "Trabajador detectado sin casco en zona de estructura"
            ),
            SafetyAlert(
                id = "alert-002",
                violationType = "NO_SAFETY_VEST",
                severity = 4,
                zoneId = "zone-B-excavation",
                timestamp = now - 28 * 60_000,
                messageEn = "Missing high-visibility vest near active excavation",
                messageEs = "Falta chaleco de alta visibilidad cerca de excavación activa"
            ),
            SafetyAlert(
                id = "alert-003",
                violationType = "FALL_HAZARD",
                severity = 5,
                zoneId = "zone-D-roofing",
                timestamp = now - 45 * 60_000,
                messageEn = "Unprotected edge detected on roofing zone — fall hazard",
                messageEs = "Borde sin protección detectado en zona de techado — riesgo de caída"
            ),
            SafetyAlert(
                id = "alert-004",
                violationType = "RESTRICTED_ZONE",
                severity = 3,
                zoneId = "zone-C-electrical",
                timestamp = now - 78 * 60_000,
                messageEn = "Unauthorized entry into restricted electrical zone",
                messageEs = "Entrada no autorizada en zona eléctrica restringida"
            ),
            SafetyAlert(
                id = "alert-005",
                violationType = "NO_SAFETY_GLASSES",
                severity = 3,
                zoneId = "zone-C-electrical",
                timestamp = now - 95 * 60_000,
                messageEn = "Safety glasses required in electrical work area",
                messageEs = "Se requieren gafas de seguridad en área de trabajo eléctrico"
            ),
            SafetyAlert(
                id = "alert-006",
                violationType = "NO_HARD_HAT",
                severity = 4,
                zoneId = "zone-E-staging",
                timestamp = now - 120 * 60_000,
                messageEn = "Hard hat not detected in staging area during crane operation",
                messageEs = "Casco no detectado en área de preparación durante operación de grúa"
            ),
            SafetyAlert(
                id = "alert-007",
                violationType = "IMPROPER_SCAFFOLDING",
                severity = 5,
                zoneId = "zone-A-framing",
                timestamp = now - 150 * 60_000,
                messageEn = "Scaffolding missing guardrails on second level",
                messageEs = "Andamio sin barandillas en segundo nivel"
            ),
            SafetyAlert(
                id = "alert-008",
                violationType = "NO_SAFETY_VEST",
                severity = 2,
                zoneId = "zone-B-excavation",
                timestamp = now - 180 * 60_000,
                messageEn = "Visitor in excavation zone without safety vest",
                messageEs = "Visitante en zona de excavación sin chaleco de seguridad"
            ),
            SafetyAlert(
                id = "alert-009",
                violationType = "HOUSEKEEPING",
                severity = 1,
                zoneId = "zone-E-staging",
                timestamp = now - 210 * 60_000,
                messageEn = "Debris accumulation in staging walkway — tripping hazard",
                messageEs = "Acumulación de escombros en pasillo de preparación — riesgo de tropiezo"
            ),
        )
    }

    fun getSafetyScore(): Int = 87

    fun getActiveWorkerCount(): Int = 24

    fun getZoneStatuses(): List<ZoneStatus> = listOf(
        ZoneStatus(
            zoneId = "zone-A-framing",
            zoneName = "Framing",
            zoneNameEs = "Estructura",
            workerCount = 8,
            activeAlerts = 2,
            safetyScore = 74
        ),
        ZoneStatus(
            zoneId = "zone-B-excavation",
            zoneName = "Excavation",
            zoneNameEs = "Excavación",
            workerCount = 6,
            activeAlerts = 1,
            safetyScore = 82
        ),
        ZoneStatus(
            zoneId = "zone-C-electrical",
            zoneName = "Electrical",
            zoneNameEs = "Eléctrico",
            workerCount = 4,
            activeAlerts = 1,
            safetyScore = 91
        ),
        ZoneStatus(
            zoneId = "zone-D-roofing",
            zoneName = "Roofing",
            zoneNameEs = "Techado",
            workerCount = 3,
            activeAlerts = 1,
            safetyScore = 68
        ),
        ZoneStatus(
            zoneId = "zone-E-staging",
            zoneName = "Staging",
            zoneNameEs = "Preparación",
            workerCount = 3,
            activeAlerts = 0,
            safetyScore = 95
        ),
    )

    fun getConnectionStatus(): ConnectionStatus = ConnectionStatus.DEMO_MODE
}

data class ZoneStatus(
    val zoneId: String,
    val zoneName: String,
    val zoneNameEs: String,
    val workerCount: Int,
    val activeAlerts: Int,
    val safetyScore: Int,
)

enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    DEMO_MODE,
}
