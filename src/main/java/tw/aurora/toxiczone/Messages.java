package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

final class Messages {
  private YamlConfiguration values;

  Messages(YamlConfiguration values) {
    this.values = values;
  }

  void replace(YamlConfiguration values) {
    this.values = values;
  }

  String text(String key, Map<String, ?> args) {
    String text = values.getString(key, key);
    for (var entry : args.entrySet())
      text = text.replace("{" + entry.getKey() + "}", entry.getValue().toString());
    return ChatColor.translateAlternateColorCodes('&', text);
  }

  void send(CommandSender sender, String key, Map<String, ?> args) {
    for (String line : text(key, args).split("\n"))
      sender.sendMessage(text("prefix", Map.of()) + line);
  }

  void send(CommandSender sender, String key) {
    send(sender, key, Map.of());
  }

  List<String> lore(String key, Map<String, ?> args) {
    List<String> lines = new ArrayList<>();
    for (String raw : values.getStringList(key)) {
      for (var entry : args.entrySet())
        raw = raw.replace("{" + entry.getKey() + "}", entry.getValue().toString());
      String line = ChatColor.translateAlternateColorCodes('&', raw);
      StringBuilder part = new StringBuilder();
      int width = 0;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (c == '§' && i + 1 < line.length()) {
          part.append(c).append(line.charAt(++i));
          continue;
        }
        int advance = c > 255 ? 2 : 1;
        if (width + advance > 42) {
          String previous = part.toString();
          lines.add(previous);
          part = new StringBuilder(ChatColor.getLastColors(previous));
          width = 0;
        }
        part.append(c);
        width += advance;
      }
      lines.add(part.toString());
    }
    return lines;
  }

  static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }
}
