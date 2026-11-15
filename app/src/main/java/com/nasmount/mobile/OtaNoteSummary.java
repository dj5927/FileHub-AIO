package com.nasmount.mobile;

/**
 * Small, readable in-app release notes. Never print GitHub boilerplate,
 * checksum, source list, build tests, or a long README into an update dialog.
 */
final class OtaNoteSummary {
    private OtaNoteSummary(){}

    static String format(String source){
        if(source==null||source.trim().isEmpty())return "새 버전이 준비됐습니다.";
        StringBuilder additions=new StringBuilder();
        StringBuilder fixes=new StringBuilder();
        StringBuilder others=new StringBuilder();
        int section=0;
        int[] lines={0,0,0};
        for(String raw:source.split("\\r?\\n")){
            String line=raw.trim();
            if(line.startsWith("#")){
                String heading=line.replaceFirst("^#+\\s*","")
                        .replace("**","").replace(":","").trim();
                if(heading.equals("추가된 기능")||heading.equals("추가된 것들")||
                        heading.equals("추가 사항"))section=1;
                else if(heading.equals("수정 사항")||heading.equals("수정한 것들")||
                        heading.equals("수정된 것들"))section=2;
                else if(heading.equals("기타")||heading.equals("기타 사항")||
                        heading.equals("기타사항"))section=3;
                else section=0;
                continue;
            }
            if(section<1||section>3)continue;
            if(!(line.startsWith("- ")||line.startsWith("* ")||
                    line.startsWith("• ")))continue;
            if(lines[section-1]>=4)continue;
            String entry=line.substring(2).replace("**","").trim();
            if(entry.isEmpty())continue;
            if(entry.length()>85)entry=entry.substring(0,82)+"…";
            StringBuilder target=section==1?additions:section==2?fixes:others;
            target.append("• ").append(entry).append('\n');
            lines[section-1]++;
        }
        StringBuilder result=new StringBuilder();
        if(additions.length()>0)result.append("추가된 기능\n").append(additions);
        if(fixes.length()>0){
            if(result.length()>0)result.append('\n');
            result.append("수정 사항\n").append(fixes);
        }
        if(others.length()>0){
            if(result.length()>0)result.append('\n');
            result.append("기타 사항\n").append(others);
        }
        return result.length()>0?result.toString().trim():"새 버전이 준비됐습니다.";
    }
}
