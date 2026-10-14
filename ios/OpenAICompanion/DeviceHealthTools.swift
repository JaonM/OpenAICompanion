import Foundation
import HealthKit

extension DeviceToolsBridge {
    func healthTool(_ name: String, _ args: [String: Any]) async throws -> [String: Any] {
        if name == "device_health_get_availability" {
            return ["available": HKHealthStore.isHealthDataAvailable(), "read_authorization": "not_disclosed",
                    "metrics": ["steps", "walking_running_distance", "active_energy", "sleep", "workouts"]]
        }
        guard HKHealthStore.isHealthDataAvailable() else { throw DeviceFailure("UNAVAILABLE", "HealthKit unavailable on this device") }
        let (start, end) = try deviceRange(args)
        let type: HKSampleType
        var unit: HKUnit?
        var unitLabel = ""
        if name == "device_health_query_statistics" {
            switch args["metric"] as! String {
            case "steps": type = HKQuantityType(.stepCount); unit = .count(); unitLabel = "count"
            case "walking_running_distance": type = HKQuantityType(.distanceWalkingRunning); unit = .meter(); unitLabel = "m"
            default: type = HKQuantityType(.activeEnergyBurned); unit = .kilocalorie(); unitLabel = "kcal"
            }
        } else if name == "device_health_list_sleep" { type = HKCategoryType(.sleepAnalysis) }
        else { type = HKWorkoutType.workoutType() }
        try await health.requestAuthorization(toShare: [], read: [type])
        try checkForeground()
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        if let quantity = type as? HKQuantityType, let unit {
            guard let zone = TimeZone(identifier: args["time_zone"] as! String) else { throw DeviceFailure("INVALID_ARGUMENTS", "Unknown IANA timezone") }
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
            let anchor = calendar.startOfDay(for: start)
            let rows: [[String: Any]] = try await withCheckedThrowingContinuation { continuation in
                let query = HKStatisticsCollectionQuery(quantityType: quantity,
                    quantitySamplePredicate: predicate, options: [.cumulativeSum],
                    anchorDate: anchor, intervalComponents: DateComponents(calendar: calendar, timeZone: zone, day: 1))
                query.initialResultsHandler = { _, collection, error in
                    if let error { continuation.resume(throwing: error); return }
                    var rows: [[String: Any]] = []
                    collection?.enumerateStatistics(from: start, to: end.addingTimeInterval(-0.001)) { stat, _ in
                        rows.append(["start": ISO8601DateFormatter().string(from: stat.startDate),
                                     "end": ISO8601DateFormatter().string(from: stat.endDate),
                                     "value": stat.sumQuantity().map { $0.doubleValue(for: unit) } as Any? ?? NSNull()])
                    }
                    continuation.resume(returning: rows)
                }
                health.execute(query)
            }
            return ["metric": args["metric"]!, "unit": unitLabel, "time_zone": zone.identifier,
                    "days": rows, "source": "HealthKit cumulative statistics", "coverage": "readable_data_only",
                    "note": "Null or absent data does not mean zero or prove permission was denied. Edge days may cover partial intervals."]
        }
        let samples: [HKSample] = try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 501,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]) { _, samples, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: samples ?? []) }
            }
            health.execute(query)
        }
        guard samples.count <= 500 else { throw DeviceFailure("RANGE_TOO_LARGE", "More than 500 samples; narrow the date range") }
        let rows: [[String: Any]] = samples.map { sample in
            var row: [String: Any] = ["start": ISO8601DateFormatter().string(from: sample.startDate),
                "end": ISO8601DateFormatter().string(from: sample.endDate),
                "source": sample.sourceRevision.source.name, "source_bundle": sample.sourceRevision.source.bundleIdentifier]
            if let sleep = sample as? HKCategorySample {
                row["stage"] = sleep.value
            }
            if let workout = sample as? HKWorkout {
                row["activity_type"] = workout.workoutActivityType.rawValue
                switch workout.workoutActivityType {
                case .walking: row["activity"] = "walking"
                case .running: row["activity"] = "running"
                case .cycling: row["activity"] = "cycling"
                case .swimming: row["activity"] = "swimming"
                case .yoga: row["activity"] = "yoga"
                case .traditionalStrengthTraining: row["activity"] = "strength_training"
                case .functionalStrengthTraining: row["activity"] = "functional_strength_training"
                case .hiking: row["activity"] = "hiking"
                case .highIntensityIntervalTraining: row["activity"] = "high_intensity_interval_training"
                default: row["activity"] = "other_see_activity_type"
                }
                row["duration_seconds"] = workout.duration
                row["distance_m"] = workout.totalDistance.map { $0.doubleValue(for: .meter()) } as Any? ?? NSNull()
            }
            return row
        }
        return ["records": rows, "sleep_stage_labels": ["0": "in_bed", "1": "asleep_unspecified", "2": "awake", "3": "core", "4": "deep", "5": "rem"], "coverage": "readable_data_only", "overlapping_sources": "preserved_not_summed",
                "note": "Empty data does not distinguish missing records from restricted access."]
    }
}
