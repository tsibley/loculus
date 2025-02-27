import json
import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

import click
import pytz
import yaml
from ena_submission_helper import (
    CreationResults,
    check_ena,
    create_chromosome_list,
    create_ena_assembly,
    create_fasta,
    create_manifest,
    get_ena_config,
)
from ena_types import (
    AssemblyChromosomeListFile,
    AssemblyChromosomeListFileObject,
    AssemblyManifest,
    AssemblyType,
    ChromosomeType,
    MoleculeType,
)
from notifications import SlackConfig, send_slack_notification, slack_conn_init
from psycopg2.pool import SimpleConnectionPool
from submission_db_helper import (
    AssemblyTableEntry,
    Status,
    StatusAll,
    add_to_assembly_table,
    db_init,
    find_conditions_in_db,
    find_errors_in_db,
    find_waiting_in_db,
    update_db_where_conditions,
)

logger = logging.getLogger(__name__)
logging.basicConfig(
    encoding="utf-8",
    level=logging.INFO,
    format="%(asctime)s %(levelname)8s (%(filename)20s:%(lineno)4d) - %(message)s ",
    datefmt="%H:%M:%S",
)


@dataclass
class Config:
    organisms: list[dict[str, str]]
    backend_url: str
    keycloak_token_url: str
    keycloak_client_id: str
    username: str
    password: str
    db_username: str
    db_password: str
    db_host: str
    db_name: str
    unique_project_suffix: str
    ena_submission_url: str
    ena_submission_password: str
    ena_submission_username: str
    ena_reports_service_url: str
    slack_hook: str
    slack_token: str
    slack_channel_id: str


def create_chromosome_list_object(
    unaligned_sequences: dict[str, str], seq_key: dict[str, str]
) -> str:
    # Use https://www.ebi.ac.uk/ena/browser/view/GCA_900094155.1?show=chromosomes as a template
    # Use https://www.ebi.ac.uk/ena/browser/view/GCA_000854165.1?show=chromosomes for multi-segment

    chromosome_type = ChromosomeType.SEGMENTED

    entries: list[AssemblyChromosomeListFileObject] = []

    if len(unaligned_sequences.keys()) > 1:
        for segment_name, item in unaligned_sequences.items():
            if item:  # Only list sequenced segments
                entry = AssemblyChromosomeListFileObject(
                    object_name=f"{seq_key["accession"]}.{seq_key["version"]}_{segment_name}",
                    chromosome_name=segment_name,
                    chromosome_type=chromosome_type,
                )
                entries.append(entry)
    else:
        entry = AssemblyChromosomeListFileObject(
            object_name=f"{seq_key["accession"]}.{seq_key["version"]}",
            chromosome_name="main",
            chromosome_type=chromosome_type,
        )
        entries.append(entry)

    return AssemblyChromosomeListFile(chromosomes=entries)


def create_manifest_object(
    config: Config,
    sample_table_entry: dict[str, str],
    project_table_entry: dict[str, str],
    submission_table_entry: dict[str, str],
    seq_key: dict[str, str],
    group_key: dict[str, str],
    test=False,
) -> AssemblyManifest:
    sample_accession = sample_table_entry["result"]["ena_sample_accession"]
    study_accession = project_table_entry["result"]["bioproject_accession"]

    metadata = submission_table_entry["metadata"]
    unaligned_nucleotide_sequences = submission_table_entry["unaligned_nucleotide_sequences"]
    organism_metadata = config.organisms[group_key["organism"]]["ingest"]
    chromosome_list_object = create_chromosome_list_object(unaligned_nucleotide_sequences, seq_key)
    chromosome_list_file = create_chromosome_list(list_object=chromosome_list_object)
    fasta_file = create_fasta(
        unaligned_sequences=unaligned_nucleotide_sequences,
        chromosome_list=chromosome_list_object,
    )
    program = (
        metadata["sequencingInstrument"] if metadata.get("sequencingInstrument") else "Unknown"
    )
    platform = metadata["sequencingProtocol"] if metadata.get("sequencingProtocol") else "Unknown"
    try:
        coverage = (
            (
                int(metadata["depthOfCoverage"])
                if int(metadata["depthOfCoverage"]) == float(metadata["depthOfCoverage"])
                else float(metadata["depthOfCoverage"])
            )
            if metadata.get("depthOfCoverage")
            else 1
        )
    except ValueError:
        coverage = 1
    try:
        moleculetype = (
            MoleculeType(metadata["moleculeType"])
            if organism_metadata.get("moleculeType")
            else None
        )
    except ValueError:
        moleculetype = None
    description = (
        f"Original sequence submitted to {config.db_name} with accession: "
        f"{seq_key["accession"]}, version: {seq_key["version"]}"
    )
    assembly_name = (
        seq_key["accession"]
        + f"{datetime.now(tz=pytz.utc)}".replace(" ", "_").replace("+", "_").replace(":", "_")
        if test  # This is the alias that needs to be unique
        else seq_key["accession"]
    )

    return AssemblyManifest(
        study=study_accession,
        sample=sample_accession,
        assemblyname=assembly_name,
        assembly_type=AssemblyType.ISOLATE,
        coverage=coverage,
        program=program,
        platform=platform,
        fasta=fasta_file,
        chromosome_list=chromosome_list_file,
        description=description,
        moleculetype=moleculetype,
    )


def submission_table_start(db_config: SimpleConnectionPool):
    """
    1. Find all entries in submission_table in state SUBMITTED_SAMPLE
    2. If (exists an entry in the assembly_table for (accession, version)):
    a.      If (in state SUBMITTED) update state in submission_table to SUBMITTED_ALL
    b.      Else update state to SUBMITTING_ASSEMBLY
    3. Else create corresponding entry in assembly_table
    """
    conditions = {"status_all": StatusAll.SUBMITTED_SAMPLE}
    ready_to_submit = find_conditions_in_db(
        db_config, table_name="submission_table", conditions=conditions
    )
    if len(ready_to_submit) > 0:
        logging.debug(
            f"Found {len(ready_to_submit)} entries in submission_table in status SUBMITTED_SAMPLE"
        )
    for row in ready_to_submit:
        seq_key = {"accession": row["accession"], "version": row["version"]}

        # 1. check if there exists an entry in the assembly_table for seq_key
        corresponding_assembly = find_conditions_in_db(
            db_config, table_name="assembly_table", conditions=seq_key
        )
        if len(corresponding_assembly) == 1:
            if corresponding_assembly[0]["status"] == str(Status.SUBMITTED):
                update_values = {"status_all": StatusAll.SUBMITTED_ALL}
                update_db_where_conditions(
                    db_config,
                    table_name="submission_table",
                    conditions=seq_key,
                    update_values=update_values,
                )
            else:
                update_values = {"status_all": StatusAll.SUBMITTING_ASSEMBLY}
                update_db_where_conditions(
                    db_config,
                    table_name="submission_table",
                    conditions=seq_key,
                    update_values=update_values,
                )
        else:
            # If not: create assembly_entry, change status to SUBMITTING_ASSEMBLY
            assembly_table_entry = AssemblyTableEntry(**seq_key)
            succeeded = add_to_assembly_table(db_config, assembly_table_entry)
            if succeeded:
                update_values = {"status_all": StatusAll.SUBMITTING_ASSEMBLY}
                update_db_where_conditions(
                    db_config,
                    table_name="submission_table",
                    conditions=seq_key,
                    update_values=update_values,
                )


def submission_table_update(db_config: SimpleConnectionPool):
    """
    1. Find all entries in submission_table in state SUBMITTING_ASSEMBLY
    2. If (exists an entry in the assembly_table for (accession, version)):
    a.      If (in state SUBMITTED) update state in submission_table to SUBMITTED_ALL
    3. Else throw Error
    """
    conditions = {"status_all": StatusAll.SUBMITTING_ASSEMBLY}
    submitting_assembly = find_conditions_in_db(
        db_config, table_name="submission_table", conditions=conditions
    )
    if len(submitting_assembly) > 0:
        logger.debug(
            f"Found {len(submitting_assembly)} entries in submission_table in"
            " status SUBMITTING_ASSEMBLY"
        )
    for row in submitting_assembly:
        seq_key = {"accession": row["accession"], "version": row["version"]}

        corresponding_assembly = find_conditions_in_db(
            db_config, table_name="assembly_table", conditions=seq_key
        )
        if len(corresponding_assembly) == 1 and corresponding_assembly[0]["status"] == str(
            Status.SUBMITTED
        ):
            update_values = {"status_all": StatusAll.SUBMITTED_ALL}
            update_db_where_conditions(
                db_config,
                table_name="submission_table",
                conditions=seq_key,
                update_values=update_values,
            )
        if len(corresponding_assembly) == 0:
            error_msg = (
                "Entry in submission_table in status SUBMITTING_ASSEMBLY",
                " with no corresponding assembly",
            )
            raise RuntimeError(error_msg)


def assembly_table_create(db_config: SimpleConnectionPool, config: Config, retry_number: int = 3):
    """
    1. Find all entries in assembly_table in state READY
    2. Create temporary files: chromosome_list_file, fasta_file, manifest_file
    3. Update assembly_table to state SUBMITTING (only proceed if update succeeds)
    4. If (create_ena_assembly succeeds): update state to SUBMITTED with results
    3. Else update state to HAS_ERRORS with error messages
    """
    ena_config = get_ena_config(
        config.ena_submission_username,
        config.ena_submission_password,
        config.ena_submission_url,
        config.ena_reports_service_url,
    )
    conditions = {"status": Status.READY}
    ready_to_submit_assembly = find_conditions_in_db(
        db_config, table_name="assembly_table", conditions=conditions
    )
    if len(ready_to_submit_assembly) > 0:
        logger.debug(
            f"Found {len(ready_to_submit_assembly)} entries in assembly_table in status READY"
        )
    for row in ready_to_submit_assembly:
        seq_key = {"accession": row["accession"], "version": row["version"]}
        sample_data_in_submission_table = find_conditions_in_db(
            db_config, table_name="submission_table", conditions=seq_key
        )
        if len(sample_data_in_submission_table) == 0:
            error_msg = f"Entry {row["accession"]} not found in submitting_table"
            raise RuntimeError(error_msg)
        group_key = {
            "group_id": sample_data_in_submission_table[0]["group_id"],
            "organism": sample_data_in_submission_table[0]["organism"],
        }
        center_name = sample_data_in_submission_table[0]["center_name"]

        results_in_sample_table = find_conditions_in_db(
            db_config, table_name="sample_table", conditions=seq_key
        )
        if len(results_in_sample_table) == 0:
            error_msg = f"Entry {row["accession"]} not found in sample_table"
            raise RuntimeError(error_msg)

        results_in_project_table = find_conditions_in_db(
            db_config, table_name="project_table", conditions=group_key
        )
        if len(results_in_project_table) == 0:
            error_msg = f"Entry {row["accession"]} not found in project_table"
            raise RuntimeError(error_msg)

        manifest_object = create_manifest_object(
            config,
            results_in_sample_table[0],
            results_in_project_table[0],
            sample_data_in_submission_table[0],
            seq_key,
            group_key,
            test=True,  # TODO(https://github.com/loculus-project/loculus/issues/2425): remove in production
        )
        manifest_file = create_manifest(manifest_object)

        update_values = {"status": Status.SUBMITTING}
        number_rows_updated = update_db_where_conditions(
            db_config,
            table_name="assembly_table",
            conditions=seq_key,
            update_values=update_values,
        )
        if number_rows_updated != 1:
            # state not correctly updated - do not start submission
            logger.warning(
                "assembly_table: Status update from READY to SUBMITTING failed "
                "- not starting submission."
            )
            continue
        logger.info(f"Starting assembly creation for accession {row["accession"]}")
        assembly_creation_results: CreationResults = create_ena_assembly(
            ena_config, manifest_file, center_name=center_name
        )
        if assembly_creation_results.results:
            update_values = {
                "status": Status.WAITING,
                "result": json.dumps(assembly_creation_results.results),
            }
            number_rows_updated = 0
            tries = 0
            while number_rows_updated != 1 and tries < retry_number:
                if tries > 0:
                    logger.warning(
                        f"Assembly created but DB update failed - reentry DB update #{tries}."
                    )
                number_rows_updated = update_db_where_conditions(
                    db_config,
                    table_name="assembly_table",
                    conditions=seq_key,
                    update_values=update_values,
                )
                tries += 1
            if number_rows_updated == 1:
                logger.info(
                    f"Assembly submission for accession {row["accession"]} succeeded! - waiting for ENA accession"
                )
        else:
            update_values = {
                "status": Status.HAS_ERRORS,
                "errors": json.dumps(assembly_creation_results.errors),
            }
            number_rows_updated = 0
            tries = 0
            while number_rows_updated != 1 and tries < retry_number:
                if tries > 0:
                    logger.warning(
                        f"Assembly creation failed and DB update failed - reentry DB update #{tries}."
                    )
                number_rows_updated = update_db_where_conditions(
                    db_config,
                    table_name="assembly_table",
                    conditions=seq_key,
                    update_values=update_values,
                )
                tries += 1


_last_ena_check: datetime | None = None


def assembly_table_update(
    db_config: SimpleConnectionPool, config: Config, retry_number: int = 3, time_threshold: int = 5
):
    """
    - time_threshold (minutes)
    1. Find all entries in assembly_table in state WAITING
    2. If over time_threshold since last check, check if accession exists in ENA
    3. If (exists): update state to SUBMITTED with results
    """
    global _last_ena_check  # noqa: PLW0602
    ena_config = get_ena_config(
        config.ena_submission_username,
        config.ena_submission_password,
        config.ena_submission_url,
        config.ena_reports_service_url,
    )
    conditions = {"status": Status.WAITING}
    waiting = find_conditions_in_db(db_config, table_name="assembly_table", conditions=conditions)
    if len(waiting) > 0:
        logger.debug(f"Found {len(waiting)} entries in assembly_table in status READY")
    # Check if ENA has assigned an accession, don't do this too frequently
    time = datetime.now(tz=pytz.utc)
    if not _last_ena_check or time - timedelta(minutes=time_threshold) > _last_ena_check:
        logger.debug("Checking state in ENA")
        for row in waiting:
            seq_key = {"accession": row["accession"], "version": row["version"]}
            check_results: CreationResults = check_ena(ena_config, row["result"]["erz_accession"])
            _last_ena_check = time
            if not check_results.results:
                continue

            update_values = {
                "status": Status.SUBMITTED,
                "result": json.dumps(check_results.results),
                "finished_at": datetime.now(tz=pytz.utc),
            }
            number_rows_updated = 0
            tries = 0
            while number_rows_updated != 1 and tries < retry_number:
                if tries > 0:
                    logger.warning(
                        f"Assembly in ENA but DB update failed - reentry DB update #{tries}."
                    )
                number_rows_updated = update_db_where_conditions(
                    db_config,
                    table_name="assembly_table",
                    conditions=seq_key,
                    update_values=update_values,
                )
                tries += 1
            if number_rows_updated == 1:
                logger.info(
                    f"Assembly submission for accession {row["accession"]} succeeded and accession returned!"
                )


def assembly_table_handle_errors(
    db_config: SimpleConnectionPool,
    config: Config,
    slack_config: SlackConfig,
    time_threshold: int = 15,
    time_threshold_waiting: int = 48,
    slack_time_threshold: int = 12,
):
    """
    - time_threshold: (minutes)
    - time_threshold_waiting: (hours)
    - slack_time_threshold: (hours)
    1. Find all entries in assembly_table in state HAS_ERRORS or SUBMITTING over time_threshold
    2. If time since last slack_notification is over slack_time_threshold send notification
    """
    entries_with_errors = find_errors_in_db(
        db_config, "assembly_table", time_threshold=time_threshold
    )
    if len(entries_with_errors) > 0:
        error_msg = (
            f"{config.backend_url}: ENA Submission pipeline found {len(entries_with_errors)} entries"
            f" in assembly_table in status HAS_ERRORS or SUBMITTING for over {time_threshold}m"
        )
        send_slack_notification(
            error_msg,
            slack_config,
            time=datetime.now(tz=pytz.utc),
            time_threshold=slack_time_threshold,
        )
        # TODO: Query ENA to check if assembly has in fact been created
        # If created update assembly_table
        # If not retry 3 times, then raise for manual intervention
    entries_waiting = find_waiting_in_db(
        db_config, "assembly_table", time_threshold=time_threshold_waiting
    )
    if len(entries_waiting) > 0:
        error_msg = (
            f"ENA Submission pipeline found {len(entries_waiting)} entries in assembly_table in"
            f" status WAITING for over {time_threshold_waiting}h"
        )
        send_slack_notification(
            config, error_msg, time=datetime.now(tz=pytz.utc), time_threshold=slack_time_threshold
        )


@click.command()
@click.option(
    "--log-level",
    default="INFO",
    type=click.Choice(["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]),
)
@click.option(
    "--config-file",
    required=True,
    type=click.Path(exists=True),
)
def create_assembly(log_level, config_file):
    logger.setLevel(log_level)
    logging.getLogger("requests").setLevel(logging.INFO)

    with open(config_file) as file:
        full_config = yaml.safe_load(file)
        relevant_config = {key: full_config.get(key, []) for key in Config.__annotations__}
        config = Config(**relevant_config)
    logger.info(f"Config: {config}")

    db_config = db_init(config.db_password, config.db_username, config.db_host)
    slack_config = slack_conn_init(
        slack_hook_default=config.slack_hook,
        slack_token_default=config.slack_token,
        slack_channel_id_default=config.slack_channel_id,
    )

    while True:
        submission_table_start(db_config)
        submission_table_update(db_config)

        assembly_table_create(db_config, config, retry_number=3)
        assembly_table_update(db_config, config)
        assembly_table_handle_errors(db_config, config, slack_config)
        time.sleep(2)


if __name__ == "__main__":
    create_assembly()
