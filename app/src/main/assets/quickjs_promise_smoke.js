let callbackResult = "pending";
Promise.resolve("resolved").then(value => {
  callbackResult = value;
});
JSON.stringify({callbackResult});
