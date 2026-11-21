#!/system/bin/sh

pid=$1
expected_uid=$2
enabled_flag=$3
target=com.samsung.android.app.smartcapture:ondemandscreenshotworker
cpu_mount=/dev/cpuctl
period=20000
current_group=
stock_group=
current_path=
pending_group=

case "$pid:$expected_uid" in
    *[!0-9:]*|:*|*:) exit 2 ;;
esac
[ "$pid" -gt 1 ] && [ "$expected_uid" -ge 10000 ] || exit 2
[ -f "$enabled_flag" ] && [ -f "$cpu_mount/cpu.cfs_quota_us" ] || exit 2

process_start() {
    IFS= read -r stat_line < "/proc/$pid/stat" || return 1
    set -- ${stat_line##*) }
    [ "$#" -ge 20 ] || return 1
    shift 19
    printf '%s\n' "$1"
}

cpu_group() {
    while IFS=: read -r _ controllers path; do
        if [ "$controllers" = cpu ]; then
            printf '%s\n' "$path"
            return 0
        fi
    done < "/proc/$pid/cgroup"
    return 1
}

worker_matches() {
    [ "$(tr '\000' '\n' < "/proc/$pid/cmdline" | head -n 1)" = "$target" ] &&
        [ "$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status")" = "$expected_uid" ] &&
        [ "$(process_start)" = "$start" ]
}

start="$(process_start)" || exit 2
case "$start" in ''|*[!0-9]*) exit 2 ;; esac
worker_matches || exit 2
group_name="gct-screenshot-analysis-${pid}-${start}"
cores="$(awk -F, '{for (i=1;i<=NF;i++) { n=split($i,a,"-"); total+=n==2?a[2]-a[1]+1:1 }} END {print total+0}' /sys/devices/system/cpu/online)"
[ "$cores" -ge 1 ] || exit 2
quota=$((period * cores / 10))

cleanup() {
    if [ -n "$pending_group" ]; then
        if [ "$(process_start 2>/dev/null)" = "$start" ] &&
            [ "$(cpu_group 2>/dev/null)" = "${pending_group#"$cpu_mount"}" ]; then
            printf '%s\n' "$pid" > "$parent/cgroup.procs"
        fi
        rmdir "$pending_group" 2>/dev/null || :
    fi
    if [ -n "$current_group" ]; then
        if [ "$(process_start 2>/dev/null)" = "$start" ] &&
            [ "$(cpu_group 2>/dev/null)" = "$current_path" ]; then
            printf '%s\n' "$pid" > "$stock_group/cgroup.procs"
        fi
        rmdir "$current_group" 2>/dev/null || :
    fi
}
trap cleanup EXIT
trap 'exit 0' HUP INT TERM PIPE

attach() {
    [ -f "$enabled_flag" ] && worker_matches || return 1
    case "$1" in /*) ;; *) return 1 ;; esac
    parent="$(realpath "$cpu_mount$1")" || return 1
    case "$parent" in "$cpu_mount"|"$cpu_mount"/*) ;; *) return 1 ;; esac
    pending_group="$parent/$group_name"
    mkdir "$pending_group" || { pending_group=; return 1; }
    printf '%s\n' "$period" > "$pending_group/cpu.cfs_period_us" || return 1
    printf '%s\n' "$quota" > "$pending_group/cpu.cfs_quota_us" || return 1
    printf '%s\n' "$pid" > "$pending_group/cgroup.procs" || return 1
    previous_group="$current_group"
    current_group="$pending_group"
    current_path="${current_group#"$cpu_mount"}"
    stock_group="$parent"
    pending_group=
    if [ -n "$previous_group" ]; then rmdir "$previous_group" 2>/dev/null || :; fi
}

attach "$(cpu_group)" || exit 2
printf 'READY %s/%s\n' "$quota" "$period"
# Android can move the worker between its native CPU profiles during the job.
# Reattach under the new profile only while this process and setting are active.
while [ -f "$enabled_flag" ] && [ "$(process_start 2>/dev/null)" = "$start" ]; do
    group="$(cpu_group 2>/dev/null)" || break
    if [ "$group" != "$current_path" ]; then attach "$group" || break; fi
    sleep 0.05
done
