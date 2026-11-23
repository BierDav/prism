//! String flags parsed as Go's `flag` package parses them: `-name value`,
//! `-name=value`, `--name` alike; parsing stops at the first non-flag or
//! after `--`; the last of a repeated flag wins.

use std::collections::BTreeMap;

/// The flags' values and the arguments after them, or `Err` for a bad or
/// unknown flag (including -h and -help) or a flag missing its value.
pub fn parse(
    args: &[String],
    names: &[&str],
) -> Result<(BTreeMap<String, String>, Vec<String>), ()> {
    let mut values = BTreeMap::new();
    let mut i = 0;
    while i < args.len() {
        let s = &args[i];
        if s.len() < 2 || !s.starts_with('-') {
            break;
        }
        let mut name = &s[1..];
        if let Some(rest) = name.strip_prefix('-') {
            if rest.is_empty() {
                i += 1; // "--" ends the flags
                break;
            }
            name = rest;
        }
        if name.is_empty() || name.starts_with('-') || name.starts_with('=') {
            return Err(());
        }
        i += 1;
        let (name, value) = match name.split_once('=') {
            Some((n, v)) => (n, Some(v.to_string())),
            None => (name, None),
        };
        if !names.contains(&name) {
            return Err(());
        }
        let value = match value {
            Some(v) => v,
            None if i < args.len() => {
                i += 1;
                args[i - 1].clone()
            }
            None => return Err(()),
        };
        values.insert(name.to_string(), value);
    }
    Ok((values, args[i..].to_vec()))
}

#[cfg(test)]
mod tests {
    use super::*;

    type Parsed = Result<(Vec<(String, String)>, Vec<String>), ()>;

    fn p(args: &[&str]) -> Parsed {
        let args: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        parse(&args, &["severity", "summary"]).map(|(v, rest)| (v.into_iter().collect(), rest))
    }

    fn kv(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    fn strs(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn go_flag_semantics() {
        assert_eq!(
            p(&[
                "--severity",
                "critical",
                "-summary=disk full",
                "brew",
                "--",
                "x"
            ]),
            Ok((
                kv(&[("severity", "critical"), ("summary", "disk full")]),
                strs(&["brew", "--", "x"])
            ))
        );
        // The first non-flag ends the flags; so does "--", which is dropped.
        assert_eq!(
            p(&["brew", "--severity", "info"]),
            Ok((vec![], strs(&["brew", "--severity", "info"])))
        );
        assert_eq!(
            p(&["--", "--severity"]),
            Ok((vec![], strs(&["--severity"])))
        );
        assert_eq!(p(&["-", "x"]), Ok((vec![], strs(&["-", "x"]))));
        // A value may start with a dash; the last repeat wins.
        assert_eq!(
            p(&["-summary", "-x-", "-summary", "y"]),
            Ok((kv(&[("summary", "y")]), vec![]))
        );
        assert_eq!(p(&["-summary="]), Ok((kv(&[("summary", "")]), vec![])));
        for bad in [
            &["-severity"][..],
            &["-h"],
            &["--help"],
            &["-unknown", "x"],
            &["---severity", "x"],
            &["-=x"],
        ] {
            assert_eq!(p(bad), Err(()), "{bad:?}");
        }
    }
}
