// Add this to the existing Apps Script project that defines
// SPREADSHEET_ID and SHEET_NAME. Keep only one doGet(e) function.
function doGet(e) {
  if (e && e.parameter && e.parameter.action === 'history') {
    return getTemperatureHistory_(e);
  }
  return jsonOutput_({ok: false, error: 'Use action=history'});
}

function getTemperatureHistory_(e) {
  try {
    const from = e.parameter.from;
    const to = e.parameter.to;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from || '') || !/^\d{4}-\d{2}-\d{2}$/.test(to || '') || from > to) {
      throw new Error('Expected from/to as YYYY-MM-DD');
    }

    const spreadsheet = SpreadsheetApp.openById(SPREADSHEET_ID);
    const sheet = spreadsheet.getSheetByName(SHEET_NAME);
    if (!sheet || sheet.getLastRow() < 2) return jsonOutput_({ok: true, readings: []});

    const rows = sheet.getDataRange().getValues();
    const headers = rows[0].map(String);
    const column = name => {
      const index = headers.indexOf(name);
      if (index < 0) throw new Error('Missing sheet column: ' + name);
      return index;
    };
    const timeCol = column('Время');
    const inputHeaders = [
      'Объем', 'Дымовой 2эт', 'Люк УГБО', 'Улица °C', 'Протечка', '2 этаж °C',
      'Камин тепловой', 'Уровень УГБО', 'Гараж ворота', 'Гараж вибро', 'Гараж дым',
      '1 этаж °C', 'Вход 13', 'Вход 14', 'Вход 15', 'Вход 16'
    ];
    const inputColumns = inputHeaders.map(column);
    const timezone = spreadsheet.getSpreadsheetTimeZone();

    const numberOrNull = value => {
      if (value === '' || value === null || value === undefined) return null;
      if (typeof value === 'boolean') return value ? 1 : 0;
      const text = String(value).trim();
      if (/^(true|on)$/i.test(text)) return 1;
      if (/^(false|off)$/i.test(text)) return 0;
      const number = typeof value === 'number' ? value : Number(text.replace(',', '.'));
      return Number.isFinite(number) ? number : null;
    };
    const readings = [];
    for (let i = 1; i < rows.length; i++) {
      const row = rows[i];
      const rawTime = row[timeCol];
      const date = rawTime instanceof Date ? rawTime : new Date(rawTime);
      if (isNaN(date.getTime())) continue;
      const day = Utilities.formatDate(date, timezone, 'yyyy-MM-dd');
      if (day < from || day > to) continue;
      readings.push({
        time: date.getTime(),
        inputs: inputColumns.map(index => numberOrNull(row[index])),
        // Retain these keys so older app versions still show their three temperatures.
        outside: numberOrNull(row[inputColumns[3]]),
        floor2: numberOrNull(row[inputColumns[5]]),
        floor1: numberOrNull(row[inputColumns[11]])
      });
    }
    readings.sort((a, b) => a.time - b.time);
    return jsonOutput_({ok: true, readings: readings});
  } catch (error) {
    return jsonOutput_({ok: false, error: String(error.message || error)});
  }
}

function jsonOutput_(value) {
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
